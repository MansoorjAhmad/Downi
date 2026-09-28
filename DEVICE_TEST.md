# 📱 DOWNI Device Test Matrix — The Law's Gate

`READ_THIS_BEFORE_UPGRADE.md` §5: **no release ships before this passes on a real phone.**
Fill it in every time the extraction layer changes, and after any UI/wiring release like a
hardship check. Mark ✅ / ❌ per cell.

## 0. V3.3 Core 2.0 — M1 on-device render gate (the owner's sheet-C1 art)

> Device pass **2026-09-27** — vivo V2058, build **48 / v3.2.0** (spike-signed). The Core is now the
> owner's art from sheet C1 (`core_orb.png`, `core_orb_paused.png`) drawn as **one bitmap per state**,
> so this cell asks one question — does the art appear, per state, carrying that state's own
> mark/energy/mood — and answers it with numbers, not a strip.

| # | Check | Evidence |
|---|---|---|
| M1-1 | The art draws at all | ✅ `CORE_ATTACH type=2032 x=452 y=1080 size=176px size_dp=64 touchable=1`; 24 shots in `test_out\core_visual` |
| M1-2 | The mark is the state's own | ✅ teal-mark px (share of inner disc): idle 1153 (12.3%) · wake/detected/pressed/dragging/snapped 1345 (14.4%) · resuming 1397–1398 (14.9%) · completing/complete 1305–1316 (14.0%) · **paused 857 (9.2%)** — the two-bar art, measured |
| M1-3 | Rose only in FAILED | ✅ disc-annulus hue: idle 188° · detected 188° · paused 191° · **failed 342°** (sat 0.29). The baked art itself measures 201° / 204°, so neither tile is rose: the rose is the FAILED tint, as designed |
| M1-4 | The render is live, not one frozen bitmap | ✅ annulus value tracks energy: idle 0.27 → detected 0.30 → paused 0.25 ("the energy receded") |
| M1-5 | Drag / snap / overlay unchanged | ⏳ not re-run in this pass — the full matrix re-runs after M2 wires the Lottie states |
| M1-6 | Ring = progress | ✅ superseded and measured — the band was re-derived for the baked art (**r = 50–56 px at 64 dp**) and the authored ring's lit arc tracks the named progress within ~5° at 25/50/75 % and closes at 100 %; see §0b M2-5 |

**Harness lessons from this pass** (four runs produced nothing before it; all four are now handled by
`tools\core_shots_live.ps1`, the driver this cell was measured with):

1. **This ROM unbinds the service by itself, ~40 s after a bind** — clean `SERVICE_UNBIND` +
   `FGS_STOP` + `SERVICE_DESTROY`, `HEARTBEAT uptime_s=30` at 10:55:28 and dead at 10:55:38, no crash
   and no command churn. The Core window dies with the binding and the debug channel lives in the
   service, so `tools\core_gate.ps1` (8 s of sleeps up front, 3 adb calls per shot) missed the window
   and wrote 28 screenshots of an empty app — which look exactly like a rendering bug. The live
   driver checks liveness before every shot and re-launches (which re-binds) when needed: the 24-shot
   pass finished in 50 s with `SERVICE_UNBIND=0`.
2. **Never arm by hand.** `settings delete` + `put secure enabled_accessibility_services` looks
   healthy — `SERVICE_CONNECTED`, `CORE_READY`, even `CORE_ATTACH` — and then the framework's delayed
   reaction to the *delete* lands ~2 s later and kills the fresh bind, while the process stays alive so
   both `pidof` and `settings get` still report healthy (2026-09-27: 10:46:38→10:46:40,
   10:51:05→10:51:08, 10:52:49→10:52:52). This ROM re-binds on its own when the app is launched:
   launch it and leave the setting alone. `fetch_diag.ps1 -Arm` proves a *new* bind, but not that the
   new bind survives.
3. **`at x y` must be pushed after `show`.** `DowniCore.show()` rebuilds its WindowManager params from
   the persisted prefs, so a position sent while hidden is silently discarded — the pass before this
   one asked for `at 452 1080` and got the persisted `x=0,y=1199`.
4. **`tools\core_state_audit.py --fixed`** (added this pass): `--at` is the window's **top-left**, and
   the disc fit is skipped. With baked art, `fit_disc`'s edge walk finds no rim stroke to lock onto in
   14 of 17 states, so the audit reported "no Core to measure" for a Core plainly on screen.

## 0b. V3.3 Core 2.0 — M2 on-device motion gate (the authored Lottie stage)

> Device pass **2026-09-27** — vivo V2058, spike-signed debug build, `tools\core_shots_live.ps1`,
> 24 shots in `test_out\core_visual_m2` (51.7 s, `CORE_ATTACH=2`, `SERVICE_UNBIND=0`).
>
> **Re-run after bug 2 below** (the layer `ip`/`op` fix) — same rig, same ROM, same 452,1080
> geometry: 24 shots in `test_out\core_visual_m2b` (61.9 s, `CORE_ATTACH=1`, `SERVICE_UNBIND=0`).
> The cells below measure that pass; where a number is written `a → b` it is before → after the fix.

| # | Check | Evidence |
|---|---|---|
| M2-1 | Every wired state loads its authored file on the phone | ✅ `CORE_STATE` carries `stage=` now: detected `core_idle_ready` · wake `core_wake` · pressed `core_press` · paused `core_pause` · complete `core_complete` · failed `core_failure` · progress `core_progress` — all seven match `CoreLottie`'s table exactly |
| M2-2 | The states with no file keep the verified static art | ✅ idle / dragging / snapped / resuming / completing log `stage=null` with no `err=`; the idle look stays the M1 art |
| M2-3 | A refusal is never silent | ✅ the reason travels on the same line (`stage=null err=<file>: <cause>`) — this is what caught the colour bug below; logcat is filtered for this app on this ROM, so the spike file is the only channel |
| M2-4 | The stage renders, not just loads | ✅ measured in pixels, pre-fix → post-fix: `core_state_complete` **0 → 1085** cyan px inside the 176 px window, `core_state_wake_mid` 0 → 837, `core_state_pressed_mid` 98 → 838, `core_state_detected` 98 → 709, `core_progress_000` 10 → 979. The control holds too: the states the stage does *not* own are pixel-identical across the two passes (idle 847 → 847, dragging/snapped/paused 927 → 927, resuming 957 → 957), so the window did not move and the change is the stage's. The owner's eye on `test_out\core_visual_m2b\_review_states.jpg` / `_review_progress.jpg` is still the aesthetic half of this cell (the timings remain Cline's reading of the sheets) |
| M2-5 | Ring = progress, numerically | ✅ measured independently of `scan_ring` (whose bands are tuned to Fetcher 1.0's stroked rim and read "no rim at all" on the authored vector ring — the M1-6 item). The authored ring's band is **r = 50–56 px at 64 dp**, and the longest contiguous lit run on that band is **82–85° at 25 %** (want 90), **174–176° at 50 %** (180), **264–266° at 75 %** (270) and a full circle at 100 % — where the pre-fix static ring reached only 266° at "100 %". The ~55° floor at 0 % is the orb's own rim light, so the *longest run* is the metric, not the total lit degrees. It also starts where §3 specifies — the run begins at 355.5° clockwise from 12 o'clock and grows clockwise at every step, so the `trim` offset is right. **Wired into `tools\core_state_audit.py` (2026-09-27):** a circle fitted to the closed ring at three Core sizes gives mid = **36.5 / 45.0 / 53.6 px** for 48/56/64 dp (max error 0.03 px, the Core centred in the window to ≤0.8 px), i.e. `mid = 0.7773 × (size_dp × density / 2) − 5.388 × density`; the arc is sampled at its inner edge (`mid − 1.45dp .. mid − 1.15dp` = 49.6..50.4 px at 64 dp) because the orb's own membrane reaches ~137 luma in that same band, and runs shorter than 25° are dropped as the art's speculars (at progress 0 the gloss fragments into 9 runs, longest 24°, where a real arc is one long run). The audit now reads **0 / 82 / 172 / 262 / 360° = 0 / 23 / 48 / 73 / 100 %** against the five named values — every one inside the original 6 pt K-A4 tolerance — giving **VERDICT 5 shots / 0 mismatches**, and the state sweep **17 shots / 0 mismatches** (rose hue 8–9 in FAILED alone, teal 184–195 everywhere else). Two tool faults came out with it: `profile --fixed` raised `TypeError` on the baked art (`r_edge` is `None` when the disc fit is skipped), and PAUSED's code-drawn-bars assertion no longer applies since the two-bar look is baked into the art (M1-2 measures it by mark px; the audit now says so instead of flagging it) |

**Three bugs this pass found and fixed** (each now gated so it cannot return):

1. **All ten JSONs were unloadable on the device.** python-lottie's `Color` is positional
   (`Color(r, g, b[, a])`), and `Color(colour)` with one tuple emitted `[[r,g,b],0,0,1]`;
   lottie-android threw `JsonDataException ... at $.layers[0].shapes[0].it[1].c.k[0]` and every
   state silently fell back to static art. `tools\core_lottie_build.py` now calls `Color(*colour)`,
   its `--check` asserts "four flat components", and `CoreLottieSpecTest` asserts the same on every
   build.
2. **The stage loaded every file and painted nothing — the one that mattered.** The layers carried no
   `ip`/`op` (python-lottie only writes them through its own add-layer path, and the generator appends
   straight to `an.layers`), so lottie-android gave each layer the composition's *end* frame as its
   out point. That lands the layer's in/out keyframe on progress 1.0 — the frame a settled,
   non-looping state holds — where `BaseLayer.setVisible(false)` hides it. Every gate shot is taken in
   exactly that state, so all 24 shots of the first pass photographed an empty window over a Core that
   honestly reported `n=3` layers and hundreds of draws. `tools\core_lottie_build.py` now writes
   `ip = 0` / `op = <composition op>` on every layer and its `--check` refuses a file without them;
   `CoreLottieSpecTest.everyLayerCarriesTheCompositionInOutPoint` re-asserts it on every build. The
   fix is verified in the shipped APK (the ten assets read back out of `app-spike-signed.apk`, 10/10
   carrying it) and on the phone (`tree=[ImageLayer:visible=true/core/core_orb.png]` where the
   pre-fix log said `visible=false`).
3. **The `core.cmd` poller races pushes.** It reads at ~2 s intervals, so two pushes closer than
   that lose the first: the driver's `show` → `at` (600 ms apart) and `state progress` →
   `progress 0` (1.8 s). A hidden Core then looks exactly like a rendering failure (`CORE_ATTACH=0`,
   every state `stage=null`). `tools\core_shots_live.ps1` pushes `show` + `at` and `state progress`
   + `progress 0` as one multi-line file — the service consumes every line it finds.

> Sign and date here when green: ______________


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

## 0c. V3.3 Core 2.0 — M4 on-device C5 motion gate (freeze / resume / completion, in time)

> Device pass **2026-09-27** — vivo V2058, spike-signed debug build (`app-spike-signed.apk`,
> installed 16:35:33), `tools\core_motion.ps1` → `test_out\core_motion` (869 frames at 30 fps from a
> 29 s screen recording, 52.2 s, `CORE_ATTACH=2`, `SERVICE_UNBIND=0`), verdict from
> `tools\core_state_audit.py frames` (`_m4_frames.log`, `EXIT=0`).
>
> The first attempt at this gate (`test_out\core_motion_a`, `_m4_frames_a.log`) failed **2 of the 4
> claims** — the freeze/resume pair — and the rig was at fault, not the Core (item 1 below). Both
> passes are kept so the fault is auditable.

| # | Check | Evidence |
|---|---|---|
| M4-0 | The instrument is alive | ✅ the motion CONTROL: the looping READY look (`core_idle_ready`) moves **move_max 32.91 ≥ 5.0** over its step, so a frozen PAUSED reading is the ring stopping, not a still screen |
| M4-1 | PAUSED freezes — no motion, same ring | ✅ **one frozen run of 167 frames = 5 533 ms** (both the disc-wide mean and the ring-annulus mean ≤ 1.0, the stricter test added with this milestone), and the arc across it reads **220° = 61 %** — the same 61 % measured before the pause (frozen runs span 176–189° in the narrow band, 220° swept over the ring's radii). Independent second instrument, two stills 2.6 s apart inside the hold: `diff` → `mean|delta| 0.00`, **0 pixels changed > 20** of 176 px window, `frozen PASS` |
| M4-2 | RESUME picks up where it stopped | ✅ RESUMING's arc measures **220° against the 220° before the pause (61 %), not 0** — the download's own percentage is the same number before and after, so the ring cannot restart |
| M4-3 | COMPLETE closes the ring before the merge | ✅ the full circle arrives at **22 633 ms** and the centre's bloom peaks at **25 367 ms** (148.6 luma against 49.4 at rest): **ring first, merge after**, then COMPLETE holds **359°**. (The merge only became visible with the `updateStage(true)` fix in item 2 — the pre-fix promotion drew `stage=null`.) |
| M4-4 | The stage's frames reach the screen | ✅ `CoreHost.verifyDrawable` + an animator update listener: a hand-drawn `LottieDrawable` is not the view's background, so `View.invalidateDrawable` used to drop every repaint the composition asked for. Before: "`core_pause` measured its first frame for 5.7 s", the merge never appeared. After: the pause's own recede plays and COMPLETE holds its 359° |

**1. The rig measured the wrong frames — and said so precisely.** The device's log stamps every
applied command: `CORE_CMD cmd=progress 62` at **17:19:42.760**, and the ring lights on the very next
frame (`f_0109`, video **3605 ms**); the video's `t=0` is device −29.5 s, which three other
`CORE_STATE` anchors pin to a frame (detected 1867 ms, resuming 11 100 ms, completing 13 567 ms).
That instant is **138 ms after the `progress62` step's window had closed**, so the step's "settled
40 %" slice still held pre-command frames and it read 24° instead of 220° — and since the freeze and
resume claims compare PAUSED/RESUMING against that reference, both failed on a Core that was drawing
correctly. Two fixes, neither of which loosens a claim: the rig now holds the two ring-bearing steps
**4.4 s** (a push is served 1.0–1.6 s later, so the change lands inside its own step), and the
analyzer watches the **ring annulus** as well as the whole disc, because a 2.6 dp stroke is ~2 % of
the disc's area — a progress-only change is nearly invisible to the disc mean. The same pair of
metrics now decides the frozen runs, so a hold that keeps the orb still while the ring's sheen keeps
turning can no longer pass as a freeze.

**2. Two app faults the first pass exposed, both invisible in the source.**
`CoreHost.updateStage(true)` on every auto-settle (COMPLETING → COMPLETE, RESUMING → PROGRESS): the
state changes under a stage that still belongs to the old state, and the promotion used to leave it
cleared, so `core_complete`'s ring-closing and merge never played on the real finish path — only when
`state complete` was typed by hand. And the `verifyDrawable` override plus the animator update
listener, without which the composition's frames are computed and dropped. `core.cmd stage` (new
bench channel, logged as `CORE_STAGE`) exists because `CORE_STATE` only carries the stage at the
instant of a change and "did that file actually play?" needs its own probe.

To re-run (device on USB; the pass takes ~52 s and the verdict ~45 s, so run it detached):

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_motion.ps1     # the pass
powershell -NoProfile -ExecutionPolicy Bypass -File _m4_analyze.ps1           # the C5 verdict -> _m4_frames.log
python tools\core_state_audit.py diff test_out\core_motion\core_motion_paused_a.png ^
       test_out\core_motion\core_motion_paused_b.png --at 452,1080 --size-dp 64 --density 2.75 --fixed
```

> Sign and date here when green: ______________

## 0d. V3.3 Core 2.0 — M5 on-device C3 touch gate (press / drag / edge snap)

> Device passes **2026-09-27** — vivo V2058, spike-signed debug build, `tools\core_touch.ps1` →
> `test_out\core_touch` (390 frames at 30 fps from a 13 s recording, 27 s, `CORE_ATTACH=1`,
> `SERVICE_UNBIND=0`), analyzed with `tools\core_state_audit.py touch`. The C3 numbers themselves are
> pinned in `CoreMotionTest.c3TouchPhysicsMatchesTheSheet` (they used to live at their call sites).
>
> **Two instruments, because they answer different questions.** Injected touch reaches the overlay on
> this ROM (`adb shell input swipe`; the tap grammar, slop and the magnet are the *service's* own
> account — `CORE_TOUCH` and `CORE_MOVED`, exact) and the pixels answer what the gel *looked* like
> (the tracker in `core_state_audit.py touch` measures the ring's centre, radius and axis ratio).

| # | Check | Evidence |
|---|---|---|
| M5-0 | The gestures reach the Core at all | ✅ `CORE_TOUCH down` / `up` on every step of the pass, and `CORE_MOVED` after each drag |
| M5-1 | Tap is not eaten by drag (plan §"Touch handling split": ~8 dp slop) | ✅ a held press at the Core's centre logged **`CORE_TOUCH down` 17:50:38.197 → `up dragging=false` 17:50:38.429** (232 ms held, still a TAP), while the 500 ms swipes logged `up dragging=true` — the platform's own `ViewConfiguration.getScaledTouchSlop()` is the threshold |
| M5-2 | Drag follows the finger | ✅ pixels: the Core's centre travelled **539 → 305** while the finger went **540 → 300** (390-frame video, `touch` mode). The service agrees (`up dragging=true`) |
| M5-3 | Edge snap magnets only near an edge (C3 §3, 12 dp) | ✅ the service's own log, with its own control: released **20 px from the left edge → `CORE_MOVED x=0 y=1080`** (magnetised to the edge); released **218 px from it → `CORE_MOVED x=218 y=1080`** (left exactly where the finger let go). Repeated identically in both passes |
| M5-4 | No trace after the gesture | ✅ the settle step measures the ring back at its rest radius (53.0 px vs the 53.0 px baseline) and the aspect back at 1.000; nothing moves after `CORE_MOVED` |
| M5-5 | Press compresses ~10 % (C3) | ✅ **measured: the art's edge fell to 50.0 px = 10.7 % of the 56.0 px rest edge** at 3133 ms of the press step, then swelled back to 56.0 px (100 % — the rebound), against sheet C3's "~10 % compression + overshoot". The thin ring could not resolve this (its crossings are narrow, partly unlit, and sit beside a fixed-radius host track, and `core_press.json` swaps the composition under it); the *whole art* scales, so the instrument is the angle-averaged radial profile's steepest falloff (`profile_radius` in `core_state_audit.py`), which is also brightness-invariant |
| M5-6 | Snap flattens on the contact axis, bulges the other | ⚠️ **not resolved by any instrument yet, and the tool now says so instead of guessing.** The vertical extent cannot be measured on this look: the art's membrane is genuinely dark along its bottom, so no column or chord scan finds it (and the chord solve is ill-conditioned at rest — 2 px of noise swings it 50 → 70 px). The mechanism is in code and gated: `CoreMotion.snapSquash(t)` peaks at exactly 0.10 and is zero at both ends, `DowniCore` applies it as `(1-env, 1+env*0.5)` on the contact axis and mirrored on the other |

**Rig facts worth keeping.** `adb shell input tap` injects **down and up 4 ms apart** (measured:
17:44:38.450 → .454), which cuts a 400 ms press composition off after 4 ms — so the press step is a
**held** swipe at one point (300 ms) instead. The position claims belong to the service's own
`CORE_MOVED` line: the pixel tracker loses the Core through the snap's 216 px glide, and the tool
prints "NOT DECIDED HERE" for those claims rather than inventing a number. And the analyzer had to be
taught the art's own look: the resting membrane is far dimmer than the download ring `is_arc` was
tuned for (its bottom edge is genuinely dark, so no column scan can find it), and the chevron inside
is *cyan too* — brighter than the membrane — so ring crossings are found by cyan-ness and paired at
the expected radius, while the *press* is measured on the whole art's edge instead.

> Sign and date here when green: ______________

## 0e. V3.3 Core 2.0 — M6 on-device C6 recovery gate (the rose → teal retry)

> Device pass **2026-09-27** — vivo V2058, the debug APK built/signed/installed that evening,
> `tools\core_c6.ps1` → `test_out\core_c6` (262 frames at 30 fps from a 9 s recording,
> `CORE_ATTACH=2`, `SERVICE_UNBIND=0`), verdict from `tools\core_state_audit.py hues`
> (`VERDICT 0 of the C6 recovery claims failed`).
>
> **What was wrong before this milestone.** `core_retry.json` (the rose → teal crossfade *with* C3's
> press/rebound) shipped in the APK and was wired to **nothing**: FAILED → (tap) → RESOLVING jumped
> straight to the resolver's orbit, so sheet C6's "tap-to-retry does a press/rebound and transitions
> rose → teal as it re-resolves" never happened. The Core now has a `RETRY` state — a transient, so
> the arbiter's `RESOLVING` push cannot cut the 600 ms acknowledgement short
> (`DowniCore.setBaseState`'s transient guard, the one the drag already used) — which plays that file
> and then settles into RESOLVING.

| # | Check | Evidence |
|---|---|---|
| M6-1 | The failure reads rose | ✅ **47 frames of rose, 1.57 s (t=1833…3367 ms), hue 10°** in the pass's colour trace |
| M6-2 | The retry's authored file actually plays | ✅ `CORE_STATE retry stage=core_retry n=2 f=0 run=true`, and its layer tree is the crossfade itself: `core_in/IMAGE/core_orb.png` (the teal fading in) over `core/IMAGE/core_orb_rose.png` (the rose fading out) |
| M6-3 | …and it reads back to teal | ✅ **633 ms after the rose ends the Core is teal, hue 195°** |
| M6-4 | The retry carries C3's press/rebound | ✅ the art's edge measured **55.0 → 50.0 px (9.1 % down), then 57.0 px (3.6 % over)** inside the file's own 600 ms — the composition authors 92 % → 104 % over 36 frames, so the device agrees with the sheet's "press/rebound" wording |
| M6-5 | No trace of the rose afterwards | ✅ **0 of 115 frames after the recovery read rose** (hue 195°, the resting look) |
| M6-6 | The unsupported read is still neutral, not rose | ✅ gated earlier: the M1/M2 sweeps measure the neutral mood (`core_unsupported`, `CoreTint`'s muted blue-grey) and `CoreLookTest.roseTintBelongsToFailureAlone` locks the rule that only FAILED — and the *start* of a RETRY — may carry the error tint. An unsupported link is deliberately **not** retried: `CoreTapAction` sends it to a fresh fetch |

**Rig lesson (applies to any state-colour pass).** The first run of this gate reported "the failure
does not read rose" while the trace plainly showed 1.6 s of it: `segments.csv` stamps each step
`push + 450 ms`, but the poller serves a push 0.4–2 s later (measured here: **1.9 s**), so a window
anchored on the push measures the *previous* phase. `hues` therefore reads its phases off the trace
itself — the longest rose run **is** the failure, what follows is the retry, what follows that is the
resting look — and uses the rig's table only to check the order. No claim in this section depends on
the push latency.

To re-run (device on USB; ~20 s, run it detached):

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_c6.ps1
python tools\core_state_audit.py hues test_out\core_c6\frames --at 452,1080 --size-dp 64 ^
       --density 2.75 --fps 30 --fixed
```

> Sign and date here when green: ______________

## 0f. V3.3 Core 2.0 — M7 full Core regression gate (C5 + C3 + C6 + M1/M2 sweep)

> Device pass **2026-09-27** — vivo V2058, build **48 / v3.2.0** (spike-signed). Full regression
> over all Core contracts on the finalized build:
> - **C5 Motion:** `test_out\core_motion` (869 frames at 30 fps), `tools\core_state_audit.py frames` → **`VERDICT 0 of the C5 motion claims failed`** (exit code 0; freeze 5.5 s at 61 %, resume 220°, completion ring close at 22 633 ms, merge bloom at 25 367 ms).
> - **C3 Touch:** `test_out\core_touch` (388 frames at 30 fps), `tools\core_state_audit.py touch` → **`VERDICT 0 of the C3 touch claims failed`** (exit code 0; rest baseline ring 53.0 px, edge 56.0 px, centre x 539.0; press compression to 50.0 px = 10.7 % at 2967 ms with rebound to 55.0 px; drag followed finger 539 → 307 px; settle back to ring 53.0 px and aspect 1.000). Evaluated at resting center `cx0` during in-place press to eliminate false specular peak drift.
> - **C6 Recovery:** `test_out\core_c6` (262 frames at 30 fps), `tools\core_state_audit.py hues` → **`VERDICT 0 of the C6 recovery claims failed`** (exit code 0; rose 1.57 s at hue 10°, retry back to teal at hue 195° in 633 ms with C3 press/rebound 55.0 → 50.0 → 57.0 px, 0 rose frames remaining after recovery).
> - **M1/M2 State Sweep:** `test_out\core_visual_m7` (17 shots), `tools\core_state_audit.py dir` → **`VERDICT 17 shot(s), 0 mismatch(es)`** (exit code 0; all states draw appropriate geometry, mark, rim hue, and ring progress).
> - **JVM Test Suite:** **114 tests / 0 failures** across 17 test suites (`./gradlew testDebugUnitTest`).

| # | Check | Evidence |
|---|---|---|
| M7-1 | C5 Motion contract | ✅ `test_out\core_motion`: freeze (5.5 s), resume (220°), completion bloom — `VERDICT 0 of the C5 motion claims failed` (exit 0) |
| M7-2 | C3 Touch contract | ✅ `test_out\core_touch`: in-place press (10.7 % compression + rebound), drag follow (539 → 307), clean settle — `VERDICT 0 of the C3 touch claims failed` (exit 0) |
| M7-3 | C6 Recovery contract | ✅ `test_out\core_c6`: rose failure (1.57 s, hue 10°), retry teal recovery (hue 195°), press/rebound, clean settle — `VERDICT 0 of the C6 recovery claims failed` (exit 0) |
| M7-4 | M1/M2 Visual sweep | ✅ `test_out\core_visual_m7`: 17 shots across all states, 0 mismatches — `VERDICT 17 shot(s), 0 mismatch(es)` (exit 0) |
| M7-5 | JVM Unit test suite | ✅ 114 tests passed, 0 failures across 17 suites (`./gradlew testDebugUnitTest`) |

> Sign and date here when green: ______________


## 0g. V3.3 Core 2.0 — the Core tap path (defect: the Reach killed the app on the first frame)

> Device pass **2026-09-27** — vivo V2058, build **48 / v3.2.0** (spike-signed), the owner's own
> report: *"when I tap the bubble the share menu opens and then nothing happens after that."*

**The defect.** `ReachLayer.createView()`'s anonymous `View` called a bare `draw(c)` from its
`onDraw`. Inside that subclass, `draw(Canvas)` resolves to the **inherited `View.draw`**, not
`ReachLayer`'s painter — so `View.draw` → `onDraw` → `View.draw` → … An infinite recursion on the
main thread, which the platform ends in `StackOverflowError` and the whole process with it. It
fired on the **first frame** of the layer, i.e. the instant the resolver engaged — right after the
share row had been clicked, which is exactly the owner's "nothing happens after that". `logcat -b
crash` proves it on every tap since the layer shipped: `FATAL EXCEPTION: main` /
`java.lang.StackOverflowError: stack size 8188KB`, alternating
`ReachLayer$4.onDraw(ReachLayer.java:229)` ↔ `android.view.View.draw(View.java:23560)`, at
13:31:14, 14:30:02, 14:31:01, 15:54:43, 15:54:53 and 20:16:21.

Fixed with the qualified call (`ReachLayer.this.draw(c)`) plus a one-shot `REACH_DRAW_FAULT` guard:
this window is painted on the main thread, where no resolver `try/catch` can reach, so a paint
fault must report and stay quiet instead of taking the app down. Same pass, same class: the
traveling node's `ValueAnimator` was never tracked, so it kept invalidating after the layer had
detached — it is `nodeAnim` now, cancelled by `cancelAnim()` on every `reachTo` / `capture` / `end`
/ `destroy` path, and every repaint goes through one guarded `invalidate()`.

| # | Check | Evidence |
|---|---|---|
| 0g-1 | A Core tap no longer kills the app | ✅ `logcat -d -b crash -T '09-27 21:03:00.000' \| grep -c AndroidRuntime` → **0**, against six `StackOverflowError` crashes on the same path before the fix |
| 0g-2 | The tap runs the whole chain again | ✅ `spike_20260927-210335.log`: `CORE_TAP action=FETCH state=detected` → `REACH_BEGIN core=88,1261` → `CHAIN_SCAN share=1` → `CHAIN_SHARE_CLICK route=action` → `CHAIN_SURFACE_OPEN after_ms=300 content=ready` → `CHAIN_COPYLINK_CANDIDATE text=copy link clickable=true` → `CHAIN_TARGET_CLICK route=gesture` → `CHAIN_CLOSE_PANEL back=true` → `CHAIN_CLIP_TRY n=1/6 focus=true got=yes` |
| 0g-3 | The run delivers and the job is real | ✅ `CHAIN_DELIVER_OK route=clipboard tap=1` \| `RUN_END delivered=true route=clipboard ms=3909`, then `CORE_JOB progress pct=1 → 23 → 47 → 92 → 98 → completing 100`, and the file on disk: `/sdcard/Movies/DOWNI/Video by memsgram9.mp4` (1 096 714 B, 21:05) |
| 0g-4 | A screen with nothing to grab still fails honestly | ✅ tap on a home-feed preview (no share row in the tree): `CHAIN_SCAN share=0` → `CHAIN_NO_SHARE_CLICK` → `CORE_RESOLVE_FAIL why=no_share_row` → `RUN_END delivered=false ms=127`, app alive, nothing downloaded |
| 0g-5 | Build and suite | ✅ `assembleDebug` green, `tools\sign_spike.ps1` → prod cert SHA-256 `4311317…`, `adb install -r` over the prod-signed build; **114 tests / 0 failures** across 17 suites |

> Sign and date here when green: ______________

## 0h. V3.3 Core 2.0 — M8 the render budget (the Core's own animation vs the vendor kill)

> Pass started **2026-09-27**, closed **2026-09-28** — vivo V2058, build **48 / v3.2.0** (spike-signed,
> 46 211 402 B, sha256 `A0777EB0…`; the installed `base.apk` on the device hashes to the same
> `a0777eb0…`). The 2026-09-27 attempt lost the phone off USB mid-pass, so the forensic cells were read
> then and the cells that MEASURE the change were run in this pass. **Two of them changed their
> instrument first** — the app did not change at all (see the two notes under the table). The forensic
> cells that motivated M8 keep their raw evidence from the first attempt.

**Note 1 — the frames counter was the wrong door.** `core_idle_cost.ps1`'s `dumpsys gfxinfo` counter is
PROCESS-wide, and this package has two windows: the Core overlay and MainActivity. Measured here, the
foreground activity alone draws **~61 fps and burns ~25 s of CPU per 20 s of wall clock**, which makes
the old cell unsatisfiable on any build. The rig now samples the Core's **own** `draws` (from the
`stage` note the M4 gates already read) and takes `-Offscreen`, which sends the activity to the back
and leaves the Core overlay shown — so the frames column means the Core.

**Note 2 — the C3 pixel tracker lost the Core, and said the Core had stopped.** `core_state_audit.py
touch` pairs the ring's two crossings on a row inside a window around the centre it last held. Through
`drag_far`'s 237 px travel the tracker fell behind (`x 539 -> 357`, then a carried-forward 357) while
the service's own line for that same gesture read `CORE_MOVED x=215` — the Core's centre at **303**,
three px from where the finger stopped, which is exactly what C3 asks for. `core_touch.ps1` now writes
the service's `CORE_TOUCH` / `CORE_MOVED` / `CORE_TAP` lines to `touch.log` beside `segments.csv`, and
the analyzer decides the **drag** and the **magnet** from those (the exact instrument, per its own
words), printing the tracker's reading next to them and saying when it lost the Core. The press and the
settle — shape, not position — stay the tracker's.



**Why.** The owner's report is that DOWNI goes slow and then the Core is simply *gone*.
`dumpsys activity exit-info com.omnidownloader.app` answers the second half with eight FORCE STOPs,
six of them naming the vendor power engine — and no crash on any of them:

```
ApplicationExitInfo #1: timestamp=2026-09-27 21:31:54.638 pid=22600 process=com.omnidownloader.app
  reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0 importance=125 pss=168MB rss=351MB
  description=stop com.omnidownloader.app due to stop by com.vivo.abe state=71 bytes trace=null
#3  21:11:53.555 pss=168MB   #4  20:31:51.620 pss=94MB    #5  20:18:56.787 pss=49MB
#9  19:28:51.900 pss=247MB   #11 18:24:05.934 stop by 23966  #13 18:23:25.021  #15 18:21:49.577
```

The process was alive for minutes each time (94–247 MB PSS), and a vendor force stop also clears
`enabled_accessibility_services` — which is exactly why the Core is gone until DOWNI is opened again
(`stopped=true`, `enabled_accessibility_services` → `null` on this phone). The exemption route is
already exhausted here: `cmd deviceidle whitelist` lists `user,com.omnidownloader.app,10548`,
JobScheduler's exemption list contains `10548`, and `appops get com.omnidownloader.app` shows
`RUN_IN_BACKGROUND: allow` + `RUN_ANY_IN_BACKGROUND: allow`. So the lever left is what the process was
doing while it waited: the one looping composition, repainting a floating window at 60 fps on every
video screen (see the M8 entry in `CHANGELOG.md`).

| # | Check | Evidence |
|---|---|---|
| 0h-1 | The ambient window really ends: the Core stops drawing once it is over | ✅ `tools\core_idle_cost.ps1 -State detected -Minutes 3 -SampleSeconds 30 -Offscreen`: **frames +18 in 90 s** — all 18 inside the first sample (the tail of the 6 s window), then `+18, +18` flat — and **the Core's own draws +7 across the same 90 s** (`core=draws=600 run=false amb=0` from the first sample on), cpu 00:11:19 → **00:11:22 (+3 s per 90 s, whole process)**, `ourWakeLocks=0`. The control on the same build: `am start` MainActivity → **+1 217 frames / 20 s (~61 fps)** and **+25 s cpu / 20 s** while the Core's own counter stayed at 481 — the climb is the app's UI and the Core is parked through it |
| 0h-2 | The budget is readable and a hidden Core is parked | ✅ `tools\core_ambient_probe.ps1` (**new**, `test_out\core_ambient_probe`, 19.3 s, `SERVICE_UNBIND=0`): `state detected` + `stage` in ONE poller tick → `core_idle_ready f=0 run=true amb=1`; **+3.2 s** → `f=71 run=true amb=1 draws=62` (≈20 fps — the 42 ms cadence, not the display's 60); **+7.3 s** → `f=118 run=false amb=0` (window over, drawable paused); `hide` + `stage` → `held=1 run=false`. `CLAIMS armed(amb=1 run=true)=2 ended(amb=0 run=false)=3 parked(held=1)=1` |
| 0h-3 | The M4/M5/M6 gates still pass at the new cadences | ✅ C5 **`VERDICT 0 of the C5 motion claims failed`** (`exit=0`); M1/M2 sweep **`VERDICT 17 shot(s), 0 mismatch(es)`** (`exit=0`); C6 **`VERDICT 0 of the C6 recovery claims failed`** (`exit=0`: rose 39 frames = 1.30 s at hue 8 → teal hue 198 in **633 ms**, its own press/rebound 55.0 → 50.0 → 57.0 px, **0 of 121 frames rose** after); C3 **`VERDICT 0 of the C3 touch claims failed`** (`exit=0`: press 50.0 px = **10.7 %** at 3000 ms then 98.2 % — and it is a TAP, `up dragging=false`; drag `CORE_MOVED x=219` → centre **307** vs the finger's 300; magnet `CORE_MOVED x=0` with the far release `x=219` as its own control; settle ring 53.0 px, aspect 1.000). One whole re-run of the three rigs: `_m7_regression.log` (C5 exit 0, C3 exit 0, M1/M2 exit 0) |
| 0h-4 | The zero-touch tap still delivers with the shorter first read | ✅ measured later the same day, on a live Reel: Instagram Reels in the front (`SESSION_START pkg=com.instagram.android`), the Core shown at `at 452 1080` / `size 64`, one real tap on it → `CORE_TOUCH up dragging=false` → `CORE_TAP session=com.instagram.android action=FETCH state=detected` → **`CHAIN_CLIP_TRY n=1/6 focus=true got=yes`** (the FIRST read had it — which is what the shorter 350 ms constant is for) → `CHAIN_CLIPBOARD got=yes text=https://www.instagram.com/reel/DdzXsmbyKgt/…` → `CHAIN_DELIVER_OK route=clipboard tap=1` → **`RUN_END delivered=true route=clipboard ms=3623`** (286 ms faster than the 3 909 ms the previous build needed) and the file landed: `/sdcard/Movies/DOWNI/Video by marvinachi (1).mp4`, 1 834 524 B at 09:54. The same walk-out read the M8 budget on a real target app: `stage=core_idle_ready f=0 run=true amb=1`, `draws=194` by the completing stage, and `held=1 run=false amb=1` again the moment it was hidden over the Reel |
| 0h-5 | The kill trigger is real, named, and not a crash | ✅ six `stop by com.vivo.abe` FORCE STOPs in `dumpsys activity exit-info` (raw lines above), no `reason=4` among them |
| 0h-6 | Build and suite | ✅ `assembleDebug` green; `tools\sign_spike.ps1` → prod cert SHA-256 `4311317…`; **115 tests / 0 failures** across 17 suites (M8 adds `CoreMotionTest.theAmbientLoopIsBudgetedAndEnds`) — re-verified on this tree **2026-09-28**: 17 suites / 115 tests / 0 failures / 0 errors |
| 0h-7 | The APK that carries it | ✅ `adb install -r -d android\app\build\outputs\apk\debug\app-spike-signed.apk` → **Success** (2026-09-28 09:00:29); local sha256 `A0777EB0…` (46 211 402 B) = the installed `base.apk`'s `a0777eb0…`; `versionCode=48 versionName=3.2.0` |
| 0h-8 | The two instruments this pass had to fix | ✅ `tools\core_idle_cost.ps1` (+ `-Offscreen`, + the Core's own `draws` column), `tools\core_ambient_probe.ps1` (**new**), `tools\core_touch.ps1` (+ `touch.log`), `tools\core_state_audit.py` (+ `read_service_moves`: the drag and the magnet are decided by the service's account, and the tracker's shortfall is printed beside them). Each re-run green after its change |
| 0h-9 | The vendor kill has not come back | ⏳ **not provable in a lab pass** — `dumpsys activity exit-info` needs a real session (hours of use, videos playing, the phone in a pocket). What this pass proves is that the drain that provoked it is gone: the loop now draws 122 frames in its 6 s and stops, where it used to draw 60 fps forever at ~55 % of a core. **One live sample since, on the shipped 3.3.2 artefact (§0u-5):** 2026-09-28 17:55:50, `reason=10 subreason=21 … description=stop com.omnidownloader.app due to stop by com.vivo.abe` — the killer still fires, so the rate is still what the real session is for |

> Sign and date here when green: **2026-09-28** — 0h-1, 0h-2, 0h-3, 0h-4, 0h-6, 0h-7, 0h-8 green;
> 0h-9 wants a real session (a day or two of normal use, then recount the vendor stops).



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


## 0i. V3.3.1 — the app's own foreground cost (the vortex stops looping) — vivo V2058

> Pass run **2026-09-28**, vivo V2058, on the build that was **already installed** — the device's
> `base.apk` sha256 `cc2c38ac…` (46 211 402 B) = `app-spike-signed.apk` as built 10:24 that morning,
> versionCode 48 / v3.2.0, prod cert `4311317…`. Nothing was rebuilt for this pass: the web-only change
> was already inside that APK, so every number below is the shipped binary's.

**Why.** M8's second half (§0h, and `V3.3_PLAN.md` §8) left the app's own foreground UI as the last
"feels slow" suspect: with MainActivity in the front `dumpsys gfxinfo` counted ~61 fps and the process
burned ~25 s of CPU per 20 s of wall clock (~1.25 cores) with nobody touching the screen.
`tools/core_web_anim.py` then asked the WebView's own DevTools socket what was animating, and the answer
was **two declarations and nothing else** — both on the home screen's hero button, both on
pseudo-elements, both `iterations: Infinite`:

    spin     div.vortex::before   transform: rotate(360deg) over a conic-gradient + -webkit-mask
    breathe  div.vortex::after    opacity .55->1 + transform: scale(.96->1.05) on a radial gradient

**What changed.** `www/index.html` only. The pair rests **still** (frozen at the keyframes' own resting
pose) and plays **one pass** on the tap that wakes them — `.vortex.waking::before` 1.1 s, `::after`
1.4 s — with one 1.5 s timer dropping the class, so a fast double-tap cannot stack wakes. Same "energy
builds, then settles" grammar as the Core's C2 wake. No Java, no asset, no API.

| # | Check | Evidence |
|---|---|---|
| 0i-1 | At rest the app draws nothing | ✅ MainActivity focused (read in the same call): `Total frames rendered: 0` over 10 s and **cpu `00:01:08` → `00:01:08`, +0 s** across that window — the window that read 606/607 frames and ~+25 s before the fix |
| 0i-2 | Nothing runs forever any more | ✅ the renderer's own account, `python tools\core_web_anim.py list`: **`0 running / 3 in the document`** — `toastLine … iter=1 finished`, two `fadeIn … iter=1 finished`. `spin` and `breathe` are **absent**; before the fix the same command printed `2 running / 5` with both at `iter=forever` |
| 0i-3 | A real touch still wakes it — and it stops again | ✅ `input tap 540 537` (the box `core_web_anim.py rect` reported for `.vortex`: 132×132 CSS at dpr 2.75, viewport 392×823, screen 393×876 → centre 540,537 device px): **126 frames** in the following 3 s, then **0 frames** in a 10 s window starting 6 s after the tap. Focus checked before the tap |
| 0i-4 | One pass each, then the class leaves | ✅ `core_web_anim.py wake` (the page's own handler clicked, sampled every 150 ms on **one** live connection): `t=+0 class='vortex waking' transition+spin+breathe, all iter=1 running` → `spin` last seen at `t=+1059` (t=1033 ms = its 1.1 s) → `breathe` last at `t=+1406` (t=1382 = its 1.4 s) → `t=+1559 class='vortex' none`. VERDICT **3 name(s), at most 3 at once; forever animations: none** |
| 0i-5 | It really is back at rest | ✅ two stills — `rest_home.png` (11:38, before any tap) and `settled_home.png` (11:44, after the tap + settle) — differ in **894 px of 2 600 640 (0.034 %)**, box `(124, 21, 745, 52)` = the status bar's clock; **below y=150: 0 differing pixels**, i.e. the page is pixel-identical to the pre-tap page |
| 0i-6 | The tap really landed on the phone | ✅ `wake_mid.png` (11:43, a real `input tap`) shows the app's own toast *"The Vortex needs a link"* with its progress hairline and the ring caught **mid-rotation** with the glow at its brightest — the toast is the tap's own consequence (`toastLine … iter=1 dur=3.5s`, which only `handleVortexClick` shows) |

Notes kept rather than hidden:

- **The first "settle" window read 52 frames, and it was the toast — not a regression.** That window
  began 3 s after the tap, i.e. inside the app's own 3.5 s `toastLine` animation that an empty clipboard
  produces. The re-run starts past it (6 s) and reads 0. `core_web_anim.py list` names the toast.
- **Playing is still expensive; *looping* was the defect.** Across this pass's three taps and two wake
  runs the process went from cpu `00:00:24` to `00:01:07` — that is the animations doing what they are
  for, and then stopping. The claim is that the app is parked, not that animating is free.
- **`wake` grew a `--dry` before any of this.** A tap on `.vortex` calls `handleVortexClick()`, which
  grabs the clipboard *after* the wake, so `--dry` asks the page's own `grabClipboardUrl()` what the tap
  would download. It read `''` — which is why this pass spent no mobile data.
- **`rect`'s device-pixel mapping is CSS × dpr with the window's origin at 0,0 — and the tap did NOT
  prove it.** All the 540,537 tap established is that a tap inside the vortex's ~363 px box wakes it,
  which *either* origin mapping would also do; §0k then measured the mapping pointing clean off the
  screen (the inspector's confirm control at device y=2416 on a 2408-px display). Treat `rect` as an aim
  a real tap must confirm, never as ground truth. (The WebView is inset — `innerHeight` 823 CSS vs
  `screen.height` 876 — which is why the assumption was worth testing in the first place.)

**The screenshot that killed the previous session (10:25 that morning).** `rest_home.png` was captured
with `& $adb exec-out screencap -p > file.png`, and PowerShell 5.1 rewrites a native command's stdout as
UTF-16 text: the file began `FF FE`, not `89 50 4E 47`, and attaching it failed the run with
`Failed to load image: cannot identify image file <_io.BytesIO object>` — *exactly* the 2026-09-24
incident `tools/safe_shot.ps1` was written for, whose own header quotes that same error string. It is
kept as `rest_home.png.broken` (`safe_shot.ps1 -Check` reports `UTF16-TEXT-BROKEN`), and every still in
this section was captured with `safe_shot.ps1 -Out` (device-side `screencap` + `adb pull`) and verified
before being read. Stills: `test_out\v331_vortex\{rest_home,wake_mid,settled_home}.png`.

> Sign and date here when green: **2026-09-28** — 0i-1 … 0i-6 green.


## 0j. V3.3.1 — C6's unsupported read: the boundary holds, and the trigger is narrower than the sheet

> Device pass **2026-09-28**, vivo V2058, the v3.2.0/48 build re-signed and installed over the
> prod-signed one (`assembleDebug` + `testDebugUnitTest` green in 23 s, `sign_spike.ps1` exit 0,
> `adb install -r` exit 0; **17 suites / 117 tests / 0 failures / 0 errors**). The service's DevTools
> socket rotated after the install (`…29416` → `…32336`), so the build this section measured is the one
> the commit carries.

**Why.** 1c of the v3.3.1 plan asked whether C6's "Unsupported variant" is wired to real detection or is
"a state that exists in code but nothing triggers it". The check found something worse than a stale
question: `DowniFetcherService` matched `why.contains("photo_post") || why.contains("unsupported")`
against `MediaUrl.reason()`'s vocabulary (`host_not_supported|not_a_media_path|tt_photo_post`), and
**only `tt_photo_post` ever matched** — so the sheet's own first case, *"a link not from
TikTok/Instagram"*, was reading as rose FAILED. `MediaUrl.isUnsupportedReason()` is that rule as a named,
tested predicate now.

| # | Check | Evidence |
|---|---|---|
| 0j-1 | The rule is pinned, not eyeballed | ✅ `MediaUrlTest` +2 tests → suite **117/0**: the three reasons bare, **prefixed** (the form the service actually passes, `rejected_<why>`) and derived from real links (`https://youtu.be/…`, an Instagram profile, a TikTok photo post) all → neutral; every resolver failure (`no_share_row`, `clipboard_empty`, `root_null`, `sheet_never_opened`, `no_copy_link`, `deliver_*`, `passive_*`) and every not-a-link clipboard (`not_http`, `unparseable`, `empty`, `null`) → rose |
| 0j-2 | A transient failure still reads rose, on the phone | ✅ a real Core tap (`CORE_TOUCH up dragging=false` → `CORE_TAP session=com.instagram.android action=FETCH state=idle`) → `CORE_RESOLVE_FAIL why=no_share_row` → rose FAILED, **twice** (12:51:33, 12:52:03) |
| 0j-3 | The two reasons this change adds are unreachable in the Core's own flow | ⚠️ **measured, and recorded as a limit**: `host_not_supported` needs a foreign link on the clipboard — one was seeded through the app's own Clipboard plugin and verified by the app's own reader (`the tap would grab 'https://youtu.be/dQw4w9WgXcQ'`), and the chain **overwrote it** with Instagram's own copy-link before reading it (`CHAIN_CLIPBOARD got=yes text=https://www.instagram.com/reel/Dd0CdGRJpWM/…`) → an ordinary delivery. `not_a_media_path` needs a non-media page's share row, and an Instagram profile page exposes none: `CHAIN_SCAN pkg=com.instagram.android share=0 copylink=0 downi=0` → `no_share_row` |

**What that means, written down rather than glossed.** The rule is now true in code, and any route that
yields those reasons reads neutral instead of rose — but the reachable trigger today is still the TikTok
photo post, because the Core only harvests URLs from surfaces that expose a share row and those hand back
media paths. So this is a **correctness fix, not a user-visible one**; it becomes visible the moment a
route produces one of those reasons (an IG `/p/` photo post through the engine's own reject, a seeded
clipboard, a future surface).

> Sign and date here when green: **2026-09-28** — 0j-1 and 0j-2 green; 0j-3 is a measured limit.

## 0k. V3.3.1 — the job card's `sweep` runs for the whole download, and the other screens are clean

> Measured **2026-09-28** on the vivo V2058, Grab tab in the front (`mCurrentFocus=…MainActivity`), with
> the renderer's own DevTools account (`tools/core_web_anim.py watch`) and the job started from the app's
> own sheet control. Full change-log: `test_out/v331_sweep/watch.log`.

**Why.** Part 3's first candidate had to be measured rather than guessed. `www/index.html` still carried
two `animation: … infinite` declarations — `.bar.live > div::after { animation: sweep 1.4s linear
infinite }` and `.skel { animation: shimmer 1.3s infinite }` — and `.bar` gets `live` from the job card
itself (`jobCardHtml`: `${job.done ? '' : 'live'}`), i.e. for the whole life of every download. The
question was whether that is the vortex defect's twin or the app's own "active" feedback.

| # | Check | Evidence |
|---|---|---|
| 0k-1 | At rest, nothing runs on this screen | ✅ before the tap: `cards=0 live=0 hidden=True  nothing running` |
| 0k-2 | The sweep runs for the **whole** job, on the Grab tab | ✅ `t=+2691 ms cards=2 live=2 hidden=False 4 running: fadeIn@card, pulseOk, sweep@div::after, toastLine`, and `sweep` is present at **every** 250 ms sample through `t=+7913`; at `t=+8276 cards=0 live=0` and it is gone |
| 0k-3 | Exactly one sweep runs | ✅ `cards=2 live=2` because the card is painted into `#activeDownloads` **and** `#queueActiveContainer`, but only one `sweep` is in the running set — the hidden tab's copy is `display:none` and animates nothing |
| 0k-4 | It stops with the job, and the tail is the app's own | ✅ completion at `t=+8276`: 26 × `confettiFly` + `pulseOk` + `toastLine` (~3.9 s), then `t=+12176 nothing running` |
| 0k-5 | Where else it can run | ✅ the updater's `bar live` (L779) sits inside `#updateProgressSection`, `class="hidden"` — it sweeps only while an update is actually downloading; `.skel` exists only as loading placeholders (3 of them during an inspect) |
| 0k-6 | The app's other screens are clean at rest | ✅ Queue: 5 `fadeIn` → parked at +1627 ms · Vault: 4 `fadeIn` + 2 `img.vault-thumb` fades → parked at +2638 ms · Settings: 1 `fadeIn` → parked at +1824 ms · Grab: 4 one-shots (incl. a leftover `shakeX`) → parked at +1638 ms |
| 0k-7 | The magnitude is **not** attributed to the sweep | ⚠️ a job window reads **421 frames / +12 s CPU per 10 s** against **0 frames / +0 s** with no job — but that window contains the app's own Python download, extraction and merge **in the same process**, so it prices "a grab", not the animation. Pricing the sweep needs a job held open, and the test reel's job lasts ~5.6 s — shorter than one CDP connect |

**Correction owed from §0i.** That section said `rect`'s CSS→device mapping was "validated by use" because
the vortex tap woke the ring. It was not: the vortex is ~363 device px across, so *either* origin mapping
lands inside it. §0k then measured the mapping pointing clean off the screen — the inspector's confirm
control sits at **device y=2416 on a 2408-px screen** (viewport y≈878 CSS against `innerHeight` 823) —
which is itself a Part 3 candidate: **the sheet's primary action is below the fold**, and a coordinate tap
cannot reach it at all.

> Sign and date here when green: **2026-09-28** — 0k-1 … 0k-6 green; 0k-7 is a measured limit.

## 0l. V3.3.1 — the snap deforms the RIGHT axis now, and the view draws it — vivo V2058

> Measured **2026-09-28** on the spike-signed debug build, `tools\core_touch.ps1 -NoRecord` (logs only,
> so the device's own account is the instrument) — after the C3 audit found the axis defect by reading
> the *previous* M5 pass's frames: a left-edge snap had **widened** the Core by ~8 % at the envelope's
> peak, because `DowniCore.finishDrag` inferred the contact axis from whether the magnet had to move it.

| # | Check | Evidence |
|---|---|---|
| 0l-1 | The contact axis is the row on a LEFT-edge snap | ✅ `13:48:37.609 CORE_SQUASH t=0.74 env=0.071957536 **sx=0.9280425**` — `sx = 1 − env`; the axis now comes from *which edge is in range* (`magnetX`) |
| 0l-2 | The envelope is the sheet's, and both ends are exactly zero | ✅ `t=0.0 env=0.0 sx=1.0` … `t=0.99 env=0.004039214 sx=0.9959608` (peak 0.10 lands between samples; `CoreMotion.snapSquash` is pinned by test) |
| 0l-3 | The view actually **draws** the deformation | ✅ `draws` climbs **9 → 13 → 17** across the snap (`CoreHost.squashDraws`, counted in `onDraw` whenever the squash is live) |
| 0l-4 | The magnet and the axis agree | ✅ the pass's own log: `CORE_TOUCH up dragging=true` 13:47:57.630 → `CORE_MOVED x=0 y=1080` 13:47:57.937 (**+307 ms** = the 300 ms snap) |
| 0l-5 | Why the *video* pass could not see it | ⚠️ a measured limit of the recorder: in the 13:47 video pass, frames spanning a whole snap are **byte-identical** (19 frames, outer extent 135 px) while `draws` climbs behind them — the encoder drops the animation's frames. The deformation is proven by the service's own account (0l-1…0l-3), the way position claims already prefer `CORE_MOVED` over the pixel tracker. |

> Sign and date here when green: **2026-09-28** — 0l-1 … 0l-4 green; 0l-5 is a measured limit, stated.

## 0m. V3.3.1 — the C6 gate re-run on the fixed build: recovery green, and the exhale ships in the file

> Measured **2026-09-28** on the spike-signed debug build, `tools\core_c6.ps1` → `test_out\core_c6`
> (190 frames, 18.8 s, `CORE_ATTACH=2`, `SERVICE_UNBIND=0`), analyzed with `core_state_audit.py hues`.
> First run of this gate since the M5 audit's fixes and since the failure file gained C6.1's exhale.

| # | Check | Evidence |
|---|---|---|
| 0m-1 | The failure reads rose, and holds | ✅ 41 frames of rose, **1.37 s** (t=1967..3300 ms), hue 9 — `the failure reads rose, as sheet C6 says  PASS` |
| 0m-2 | The retry returns to teal | ✅ **633 ms** after the rose ends: hue 198 — `the rose reads back to teal, as C6 asks  PASS` |
| 0m-3 | The retry carries its own press/rebound | ✅ edge 54.0 → 50.0 px (7.4 % down), then 57.0 px (5.6 % over) inside its 600 ms — PASS |
| 0m-4 | No trace of the rose afterwards | ✅ 0 of 45 frames still rose (hue 195) — PASS. **`VERDICT 0 of the C6 recovery claims failed`** |
| 0m-5 | The states' authored files really play | ✅ the service's own log: `CORE_STATE failed stage=core_failure` → `CORE_STATE retry stage=core_retry` → `CORE_STATE idle stage=null` (with `core_orb.png` **and** `core_orb_rose.png` both in the failed composition) |
| 0m-6 | C6.1's quiet exhale | ⚠️ authored (`core_lottie_build.py`: shift by f14, settle to 97.5 % at f21, rest 98.5 % at f30) and contract-validated (10/10 states) — but deliberately **below this trace's resolution**: the rose run reads a flat 54.0 px across 1.37 s. Its proof is the file and the play (0m-5), never a pixel claim. |

> Sign and date here when green: **2026-09-28** — 0m-1 … 0m-5 green; 0m-6 stated as authored + played.

## 0n. V3.3.1 — the Reach on a real session: it fires on TikTok, and what the pixels cannot say

> Measured **2026-09-28** on the spike-signed debug build with the new `tools\core_reach.ps1`:
> relaunch → Core on screen → the platform app on a video → record 7 s → one tap on the Core's centre →
> pull + extract (210 frames) → grep the service's own log.

| # | Check | Evidence |
|---|---|---|
| 0n-1 | A tap with a real session engages the Reach | ✅ `CORE_TAP session=com.zhiliaoapp.musically action=FETCH state=detected` → **`REACH_BEGIN core=540,1168`** (the live Core's own centre) |
| 0n-2 | TikTok is the surface that can carry a session | ✅ its tree yields what the resolver needs; **Instagram's Reels view cannot** — `STEP3_DUMP_8..14 confidence=NONE note=no_url_or_id_in_tree` on the Reel URL *and* the `/reels/<id>/` permalink, where the Core honestly refuses (`CORE_TAP session=null` → `CORE_TAP_NO_SESSION`) |
| 0n-3 | A session without a share row still fails honestly | ✅ `CHAIN_SCAN pkg=com.zhiliaoapp.musically share=0 copylink=0 downi=0` → `CHAIN_NO_SHARE_CLICK` → `RUN_END delivered=false route=- ms=149`; no crash, nothing downloaded |
| 0n-4 | The tether's pixels | ⚠️ not isolated, and stated: the chain aborted at 149 ms (0n-3), so only the grow phase could play — and a frame-difference probe cannot separate a thin filament from a **playing video** (the content changed 20 000–90 000 px per frame by itself, `test_out/_reach_probe.txt`). The tether's proof stays the service's account (0n-1, §0g-2). |

> Sign and date here when green: **2026-09-28** — 0n-1 … 0n-3 green; 0n-4 is a measured limit, stated.

## 0o. V3.3.1 — the full M7 regression re-run on the fixed build — vivo V2058

> Measured **2026-09-28**, spike-signed debug build, after the M5 audit's two fixes, the tracker and
> verdict upgrades, and the failure file's exhale. Four passes + the JVM suite, the same shape as §0f.

| # | Contract | Evidence |
|---|---|---|
| 0o-1 | M1/M2 state sweep | ✅ `tools\core_shots_live.ps1`: 17 shots in 53.0 s, **`CORE_ATTACH=1 SERVICE_UNBIND=0`** |
| 0o-2 | C5 motion (in time) | ✅ `tools\core_motion.ps1`: 48.0 s, `CORE_ATTACH=1`, `SERVICE_UNBIND=0`; **`VERDICT 0 of the C5 motion claims failed`** — freeze 5 333 ms in one run with the arc still at 61 %, resume 220° = the arc before the pause, the ring closing at 21 867 ms with the merge at 22 567 ms (ring first), full circle held after |
| 0o-3 | C3 touch | ✅ `tools\core_touch.ps1` → `VERDICT 0 … failed, 2 not decided here` — press 10.7 % at 3 000 ms with the rebound, drag followed the finger (539 → 301 vs 540 → 300), the magnet only near an edge (`x=0` vs its own `x=214` control), and the two claims the recorder/tracker cannot see now say so instead of passing |
| 0o-4 | C6 recovery | ✅ `tools\core_c6.ps1` → **`VERDICT 0 of the C6 recovery claims failed`** (rose 1.37 s hue 9 → teal 633 ms; the retry's press/rebound 54 → 50 → 57 px; no trace) — §0m |
| 0o-5 | The Reach, on a real session | ✅ `tools\core_reach.ps1` (new) → `CORE_TAP session=com.zhiliaoapp.musically` → `REACH_BEGIN core=540,1168` — §0n |
| 0o-6 | The delivered states really play | ✅ the service's own log across the passes: `stage=core_idle_ready` (amb=1), `core_progress` (scrubbed, f=74 at p=0.62), `core_pause`, `resuming stage=null` (the static path), `completing stage=null`, `core_complete`, `core_failure`, `core_retry`, `idle` |
| 0o-7 | JVM suite | ✅ **17 suites / 117 tests / 0 failures** |
| 0o-8 | The release build itself | ✅ `assembleRelease` green, `apksigner verify --print-certs` → **`431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5`**, `CN=Manso, O=OmniDownloader`; installed **in place** over the existing app (no uninstall) and the phone then reports **3.3.1 / versionCode 49** |

> Sign and date here when green: **2026-09-28** — 0o-1 … 0o-8 green.

## 0o-b. V3.3.1 — the RELEASE build's own pass (the launch gate) — vivo V2058

> The one gate no rig can drive: the bench channels and the screenshot capability are `BuildConfig.DEBUG`
> -only (`DowniFetcherService`:63-65, `src/main/res/xml/fetcher_service.xml`), so every other cell in this
> file is the *debug* build. `app-release.apk` (versionCode 49, versionName 3.3.1, signed with the
> unchanged key `4311317…`) was installed **in place over v3.2.0** — no uninstall — and driven by hand
> (`adb shell input tap`) with the phone's own tooling.

| # | Check | Evidence |
|---|---|---|
| 0o-b-1 | It installs over 3.2.0 without an uninstall | ✅ `adb install -r` → `Success`; `dumpsys package` then reads `versionCode=49 versionName=3.3.1` |
| 0o-b-2 | The Core appears over a real platform session | ✅ TikTok in the front (`mCurrentFocus=com.zhiliaoapp.musically/…SplashActivity`): `core_state_audit.py shot` → `rim rgb(29,163,177) hue=186 sat=0.84 -> teal`, `mark 1445 px teal = 15.5 %`, `ring lit 17 %` (nothing downloading — honest), `VERDICT 1 shot(s), 0 mismatch(es)` |
| 0o-b-3 | One tap, and it delivers for real | ✅ `adb shell input tap 540 1168` (the Core's measured centre) → **a new file on disk: `/sdcard/Movies/DOWNI/2026 TikTok Monitization … .mp4`, 3 251 870 B at 14:02**, and the next shot's ring reads `painted 360 deg = 100 %` — the job's real progress and its COMPLETE hold |
| 0o-b-4 | The tap does not kill the app (the §0g defect, in release) | ✅ `pidof com.omnidownloader.app` → 19238 after the tap; `logcat -b crash -T '09-28 13:59:00.000'` → no `AndroidRuntime`/`FATAL` |
| 0o-b-5 | A surface with nothing to grab still refuses honestly | ✅ an earlier tap on a TikTok feed clip whose tree exposed no share row: no new file, no fake progress, no crash — the honest `share=0` path measured in §0n-3 |

> Sign and date here when green: **2026-09-28** — 0o-b-1 … 0o-b-5 green. **The release-build pass is closed.**

## 0p. V3.3.1 — the published APK's smoke (the Law §9's post-release cell) — vivo V2058

> The CI-built artefact itself: `DOWNI-v3.3.1.apk` (41 995 009 B) fetched from the release
> (`gh release download v3.3.1`), its signature verified **before** installing it, then smoked by hand.

| # | Check | Evidence |
|---|---|---|
| 0p-1 | The published APK is the prod-signed build | ✅ `apksigner verify --print-certs` → `CN=Manso, O=OmniDownloader` / SHA-256 `4311317…` — the same key as every release, so the updater's promise holds |
| 0p-2 | It installs over the previous version without an uninstall | ✅ `adb install -r DOWNI-v3.3.1.apk` → `Success`; `dumpsys package` reads `versionCode=49 versionName=3.3.1` |
| 0p-3 | A tap on the published build's Core delivers | ✅ TikTok in the front, one `input tap 540 1168` → **two real files in `/sdcard/Movies/DOWNI`: `tiktok_7685706012287880470.mp4` (6 879 033 B, 14:13) and a 3 475 019 B clip (14:12)** |
| 0p-4 | The taps did not crash it | ✅ `pidof com.omnidownloader.app` → 21697 after the taps; `logcat -b crash -T '09-28 14:05:00.000'` → empty across both |
| 0p-5 | The release page is correct | ✅ `gh release view v3.3.1` → title `DOWNI v3.3.1`, `prerelease: false`, `asset: DOWNI-v3.3.1.apk`, published 2026-09-28T09:06:28Z; both workflows green (`Release DOWNI APK` 2 m 7 s, `Test — Engine Smoke + Debug Build` 1 m 45 s) |

> Sign and date here when green: **2026-09-28** — 0p-1 … 0p-5 green. **v3.3.1 is launched.**

## 0p-b. V3.3.1 — the PUBLISHED build's smoke (V5, the Law's §9 gate) — vivo V2058

> The CI-built `DOWNI-v3.3.1.apk` (41 995 009 B, ~2.8 MB leaner than the local release build — different
> toolchain, same commit) was downloaded from the GitHub release, signature-checked, installed **in place
> over the version already on the phone** — no uninstall — then tapped twice inside real TikTok sessions.

| # | Check | Evidence |
|---|---|---|
| 0p-b-1 | The published artefact is signed with the unchanged key | ✅ `apksigner verify --print-certs`: `DN: CN=Manso, O=OmniDownloader, C=US`, `SHA-256 431131731d…` — the same digest every release has carried since 3.0.x |
| 0p-b-2 | It installs over the previous version without an uninstall | ✅ `adb install -r DOWNI-v3.3.1.apk` → `Success`; the phone then reads `versionCode=49 versionName=3.3.1` |
| 0p-b-3 | A tap inside a real TikTok session delivers, on the **published** build | ✅ two taps, two files: `/sdcard/Movies/DOWNI/tiktok_7685706012287880470.mp4` (**6 879 033 B**, 14:13) and `…The Infinite Pencil Loop…mp4` (3 475 019 B, 14:12) |
| 0p-b-4 | No crash across them | ✅ `logcat -b crash -T '09-28 14:05:00.000'` → nothing; `pidof com.omnidownloader.app` → alive (21697) |
| 0p-b-5 | The workflows that built it were green | ✅ `gh run list`: `Release DOWNI APK` (v3.3.1) **completed success** 2m07s; `Test — Engine Smoke + Debug Build` (v3.3.1) **completed success** 1m45s; `gh release view v3.3.1` → `prerelease: false`, asset `DOWNI-v3.3.1.apk`, notes = the tagged commit's message |

> Sign and date here when green: **2026-09-28** — 0p-b-1 … 0p-b-5 green. **v3.3.1 is shipped.**

## 0q. V3.3.1 — the completion pass (F1–F5, M3) — vivo V2058, debug build `A5A87459…`

> Same version, same versionCode 49, no new release: the polish, the state table and the two remaining
> looping animations, measured on the **debug** build (`app-spike-signed.apk`, sha256 `A5A87459…`) with
> the WebView's own DevTools socket (`tools/core_web_audit.py`, `tools/core_web_anim.py`) and `dumpsys
> gfxinfo` + `ps` for cost. Every number below is a measurement, not a review note.

| # | Check | Evidence |
|---|---|---|
| 0q-1 | **F1 — the inspector's primary action can be tapped without scrolling first** | With eight quality rows inflated into the open sheet: `#btnConfirmDownloadQuality`'s top was **+87 px past the fold before, −144 px above it after**; the sheet still scrolls (scroller 946 → 962 px) and at full scroll the last row's bottom (655) sits above the action bar's top (667) → **0 px occluded** |
| 0q-2 | **F2 — the touch-target sweep, four screens, before → after** | Grab **6 of 18 → 5 of 19**; Queue **3 of 10 → 2 of 10**; Vault **30 of 58 → 2 of 58** (grid) and **2 of 48** (list); Settings **13 of 31 → 7 raw / −2 covered by a ≥48 px hit area / 5 real**. The remaining rows are the version badge, the platform chips and the status pills — **labels by the standing ruling**, not controls. Horizontal **overflow `+0.0 px` on all four screens**. Fixed: header settings 40 × 48 → 48 × 48, player close 40 → 48, Vault filter chips 28.5 → 48 tall, Vault item icon buttons 35.5 → 48 wide (7 of them), Vault rescan 36 → 48, Vault search/sort 39.5/36.7 → 48, AMOLED/clipboard checkboxes → the `<label>` row is the target (59.7 px), accent swatches 28 → 48 with the dot still 28, About rows 38.7 → 48 |
| 0q-3 | **F2's own regression, caught by the next audit run** | Growing the Vault's icon buttons squeezed the card's `flex-1` **Play button 69 → 40.9 px wide** — one defect traded for another. `.btn` gained `min-width: 48px` and the action row now wraps: **148.9 × 48** Play on its own line, share/delete beneath. Re-audited green |
| 0q-4 | **F5 — the last two looping animations now rest** | On a visible screen with the *old* declarations: the progress **sweep** cost **565 frames / 9 s and +12 s CPU per 9 s** (~1.3 cores) for one 8 px bar, the four **skeletons** 557 frames / +13 s. Both are compositor-only `transform`s now **and** the shimmer is bounded (4 passes, then it rests): frames **303 → 0** across the two 9 s windows, i.e. window 2 is `0 frames` and `+1 s` of CPU. A compositor-only transform *that loops* was still 563 frames / +11 s — the price is the 60 fps, not the property, which is why "stop" is the fix and "cheaper" is not |
| 0q-5 | **M3 — the state machine has a type and a table** | `CoreStates.Kind` (REST/TRANSIENT/HOLD/TOUCH), `settleTarget()` and `isLegal()`; `CoreHost.settle()` and `isTransient()` both read the one table now; illegal transitions are reported as `CORE_TRANSITION … legal=false` (reachable only from the bench channel — the arbiter goes through `setBaseState`'s beat guard). Wire names unchanged: `CORE_READY` still prints **all fourteen** in order |
| 0q-6 | **M3 — the JVM suite and the M7 regression** | `:app:testDebugUnitTest` → **18 suites, 123 tests, 0 failures** (was 17/117; the new `CoreStatesTest` is 6 of them). Then `tools/core_motion.ps1` on the same build, `core_state_audit.py frames … --fixed`: **`VERDICT 0 of the C5 motion claims failed`** — control PASS (DETECTED move_max 19.78 ≥ 5.0), freeze PASS (**184 frames in one frozen run, longest 6100 ms**, arc 222°), resume PASS (**arc 221 = the 221 before the pause**, not 0), complete PASS (closed circle from 22600 ms, held 3833 ms, the merge peaking 148.1 vs 48.6 at rest) |
| 0q-7 | The freeze claim's second instrument | The two PAUSED stills are **byte-identical** (`580852` B each, 3 s apart) |
| 0q-8 | The packaged web state matches the source | `www/index.html` and `android/app/src/main/assets/public/index.html` are **byte-identical** — **`B11770B0…`, 180 230 B** at the final build (`C4B3ECB5…` was the F1/F2 build, before F2's own regression fix moved the line). Re-checked at every rebuild, and re-checked a third time from **inside the release APK** in §0r-3 |
| 0q-9 | Nothing was pushed | The work is local: `git status` shows the edits and **no push, no tag, no release** — the release page was deleted by the owner, and this pass deliberately does not recreate or advance it |

**What this cell does not claim.** Both instruments used here are debug-only, for two different reasons.
The WebView audit speaks CDP, and a signed release APK is not debuggable — the platform never opens the
DevTools socket for it. The M7 motion rig needs no socket at all (it screenrecords and pushes a file into
`fetch-spike/core.cmd`), but that channel is gated at its source: `if (!BuildConfig.DEBUG) return;` —
*"Phase G: the bench channel never ships"* (`DowniFetcherService.java:1239`, `:1363`). So the four-screen
numbers and the two animation costs were measured on the debug build, and the release build's own check is
§0r instead — a deliberately different instrument: the artefact's own hashes, its screencaps, and the
phone's counters. One item also stays open on purpose: the **§0h vendor-kill recount (0h-9)** — how often
the vivo ABE killer takes the process — needs a longer window than this pass had, so it is carried forward
rather than guessed at.

> Sign and date here when green: **2026-09-28** — 0q-1 … 0q-9 green on the debug build `A5A87459…`.

## 0r. V3.3.1 — the completion pass, the **release** build — vivo V2058, signed `app-release.apk`

> Same version, same versionCode 49, same keystore. This row exists because V3's whole point is that the
> release build must behave like the debug one — and this is how that is checked when the release build
> cannot be audited from inside: `aapt dump badging` prints **no `application-debuggable` flag** for it, so
> no DevTools socket, so no CDP. What is left is stronger in one place and weaker in another: the artefact
> proves *which bytes shipped*, the screencaps prove *what a thumb sees*, and `gfxinfo`/`ps` prove *what it
> costs*.

| # | Check | Evidence |
|---|---|---|
| 0r-1 | The release build is signed with the unchanged key | `:app:assembleRelease` green; `apksigner verify --print-certs` → `DN: CN=Manso, O=OmniDownloader, C=US`, `SHA-256 431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5` — the same digest as §0p-1, so signing continuity holds |
| 0r-2 | It installs **in place** over the published build | `adb install -r` → `Success`, no uninstall; the phone reads `versionCode=49 versionName=3.3.1` |
| 0r-3 | **The artefact carries the audited web state** — the check that stands in for the socket | `assets/public/index.html` **extracted from the APK**: **`B11770B094CBC937FA9B91C298399F…`, 180 230 B** — byte-identical to `www/index.html` and to `android/app/src/main/assets/public/index.html` (§0q-8). A release build cannot be measured from inside; it can be proved to contain the thing that was |
| 0r-4 | F1 holds in the shipped artefact | `test_out/v331_completion/release_inspector.png`: a real Instagram list (Best Available HD / Up to 1080p Full HD / 720p / Audio Track (MP3 / M4A)) scrolling behind the pinned **Download Best Available Quality (HD) / Cancel** row — the action is reachable without scrolling first |
| 0r-5 | F2's new 48 px gear lands on Settings | tap at the header gear → Settings photographed (`test_out/v331_completion/release_settings.png`); Grab photographed (`test_out/v331_completion/release_grab.png`) |
| 0r-6 | **The release build rests** | With `topResumedActivity=…/.MainActivity` read in the same call: `dumpsys gfxinfo … reset` + 10 s → **`Total frames rendered: 0`**, `Janky frames: 0 (0.00%)`, `Total ViewRootImpl: 1`; `/proc/<pid>/stat` utime+stime **21 ticks = 0.21 s** across the window (~2 % of one core — the process's own housekeeping). The pre-fix declarations read **565 frames and +12 s CPU per 9 s** (§0q-4) |
| 0r-7 | No crash across the pass | `logcat -b crash -T '09-28 00:00:00.000'` → **nothing**; the buffer's only entries are older than today (`09-27 13:31:14`, `09-27 20:16:21`); `pidof com.omnidownloader.app` → alive (**14163**) before and after, and `ps -A` shows that same pid, so the process in the numbers above is the one that was installed |
| 0r-8 | The artefact is recorded locally and **nothing is published** | `android/app/build/outputs/apk/release/app-release.apk` → `test_out/v331_completion/DOWNI-v3.3.1-local-completion.apk`, sha256 **`15F14D0EA1C0AD59C9C74E7210F62F467717E2817F23D7B05C010E21541C7DC4`**, **44 809 989 B**. **No push, no tag, no release** — the GitHub release was deleted by the owner, and this pass deliberately does not recreate or advance it |

**What §0r deliberately does not repeat.** The four-screen sweep and the M7 motion rig are not re-run on
this build, and for two different reasons. The sweep speaks CDP, which a non-debuggable app does not
expose. The rig's recording and analysis sides are build-agnostic — `screenrecord` plus
`core_state_audit.py` work against anything — but its scripted half pushes `fetch-spike/core.cmd`, and that
channel is compiled out of a release build: `if (!BuildConfig.DEBUG) return;` — *"Phase G: the bench channel
never ships"* (`DowniFetcherService.java:1239`, `:1363`). So the rig could not have *driven* this build even
with a socket, which is the honest version of "the release build is not the bench build". The gap is closed
by 0r-3 instead — the release APK is proved to carry the exact bytes §0q audited — and by 0r-4/0r-5, which
are what a thumb actually does on the shipped build. The two ways a release build can genuinely differ from
a debug one — the packaged web state, and the platform's treatment of a non-debuggable app with no bench
channel — are the two things checked here.

> Sign and date here when green: **2026-09-28** — 0r-1 … 0r-8 green on the signed release build, and the
> local APK is parked in `outputs/apk/release/` with its sha256 above until the owner publishes it.

## 0s. V3.3.1 — the end-to-end pass: a real link, all the way into the Vault — vivo V2058

> Measured **2026-09-28**, once on the **debug** build (§0q's `app-spike-signed.apk`) and once on the
> **shipped release** build, driven by hand (`adb shell input tap`) because the release build has no bench
> channel and no DevTools socket. Every other cell in this file asks a rig a question; this one asks the
> question a thumb asks — paste a link, read the inspector, tap the CTA, find the file, find it in the
> Vault — and then compares the two builds' output **bytes**.

| # | Check | Evidence |
|---|---|---|
| 0s-1 | The whole chain runs on the debug build | typed `https://www.instagram.com/reel/DdbMO05yk3x/` into `#inputManualUrl` (540,1090) → `#btnInspectLink` (540,1289) → the inspector resolved **real Instagram metadata** with its quality lanes → `#btnConfirmDownloadQuality` → the file landed (`test_out/e2e_typed.png`, `e2e_inspector.png`, `e2e_progress.png`, `e2e_queue.png`; the CTA's own rects from `test_out/e2e_rects_grab.json`) |
| 0s-2 | The ledger tells the truth afterwards — the N9 size fix, live | debug Queue: `statActive 0 / statDone 3 / statSize 16` **MB GRABBED**, led by *Video by marvinachi · Gallery / Movies · 28/09/2026 · SAVED*. 16 ≈ 3.6 + 9.2 + 3.6, and the only thing this pass added was the 3.6 MB job — so the completion event carried its real size, which is what N9 was about |
| 0s-3 | **The installed binary is the artefact** | `pm path` → `/data/app/~~COsHZ…/base.apk`, hashed **on the device**: `sha256 15f14d0ea1c0ad59c9c74e7210f62f467717e2817f23d7b05c010e21541c7dc4`, **44 809 989 B** — the same digest as `app-release.apk` and as `test_out/v331_completion/DOWNI-v3.3.1-local-completion.apk` (§0r-8). `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` — **no `DEBUGGABLE`**, so the build under test is the non-debuggable one |
| 0s-4 | The same chain, the same URL, on the shipped build | release install in place (`lastUpdateTime 16:53:58`, the phone reads `versionCode=49 versionName=3.3.1`); `test_out/e2e_release_typed.png` → `#btnInspectLink` → `ui_inspector.xml`: `#btnConfirmDownloadQuality` sits inside `inspectorActionBar` `[1911,2263]` — **F1's pinned row, above the fold, on the shipped artefact** → tap → `/sdcard/Movies/DOWNI/Video by marvinachi (6).mp4`, **3 608 924 B at 17:02** (`e2e_release_inspector.png`, `e2e_release_progress1.png`) |
| 0s-5 | The shipped build's file is byte-identical to the debug build's | `sha256 DC816AC92AEEE8E0CEE25BD7F669D38A7435312602A51AEFEB186C521FC05F70` for all three: the release run's `(6)` (`test_out/e2e_release/marvinachi-6-release.mp4`), the debug run's `(5)` (`test_out/e2e/marvinachi-5.mp4`) and the `(4)` baseline pulled before the change (`test_out/e2e/marvinachi-4-baseline.mp4`) |

| 0s-6 | It is a real video, not a stub | `tools\ffprobe.exe` on the pulled file: **h264 720×1280 @ 30 fps + aac stereo**, `duration=15.717052`, `bit_rate=1836947`, `size=3608924`, `format_name=mov,mp4,m4a,3gp,3g2,mj2` |
| 0s-7 | The Vault indexes it, on the shipped build | `ui_vault.xml`: the grid's **first tile** is `Video by marvinachi (6).mp4` · `3.4 MB · 28/09/2026 · Movies DOWNI` with its own Play row; `vaultCountText` **12 items · 45.2 MB**, `vaultStorageText` `🎬 45.2 MB (100%) · 🎵 0 MB (0%)` (`e2e_release_vault.png`) |
| 0s-8 | The counters and the toast fired | release Queue (`ui_queue.xml`, `e2e_release_queue.png`): `statActive 0 / statDone 4 / statSize 19` **MB GRABBED** — 16 + 3.6 → 19, one job more than the debug read — with the in-app toast `Saved ✓ / Video by marvinachi · Gallery / Movies` captured in the same window |
| 0s-9 | Nothing crashed in either pass | `logcat -b crash` **empty** before and after; `pidof` = **20054** (the pid the install created) across the whole release pass and still alive at the end; it is the only app process in `ps -A` |
| 0s-10 | The 69-line `libsigchain` block, read instead of feared | `09-28 17:01:55.177 21476 21459 E libsigchain: Setting SIGSEGV to SIG_DFL` followed by 68 frames — characterised below, and it is **not** a crash |

**The `libsigchain` block is a `sigaction` *call*, not a signal.** The line says what it is — *Setting*
SIGSEGV to SIG_DFL — and frames `#00 LogStack() ← #01 sigaction+220` are both inside `libsigchain.so`, the
ART library that chains signal handlers, reporting the change. The caller is CPython's `subprocess` C
helper: `#02–#05 _posixsubprocess.cpython-311.so` under `#07 _PyObject_MakeTpCall` /
`#08 _PyEval_EvalFrameDefault`, reached from Java through `com.chaquo.python.PyObject.callAttr` →
`DowniEnginePlugin.lambda$extract$13` on a `ThreadPoolExecutor` worker — i.e. the **forked child's standard
pre-`exec` signal reset** inside the extract path. No source in this repo spawns a process (no `subprocess`
/ `Popen` / `ProcessBuilder` / `Runtime.getRuntime` anywhere under `android/app/src/main`), so the caller is
bundled Python code reached through the plugin. That it is not a crash is not an opinion: the whole buffer
holds exactly **one** `Setting SIG…` line and **no** `Fatal signal`, **no** `*** *** ***` and **no**
`debuggerd`; `-b crash` is empty (`/data/tombstones` was not even needed); the child is gone from `ps` by the
end — the only app pid is 20054 — and the job it belonged to produced the verified file in 0s-5.

**What this pass does not claim.** The release build exposes no bench channel and no WebView socket
(`if (!BuildConfig.DEBUG) return;`, §0r), so no row above is a rig cell — it is what a thumb does. The
in-flight percentage is the *debug* build's (`test_out/e2e_progress.png`); the release build's own mid-job
frame reads `instagram.com 100 %` on the *Active downloads* card, which is the job's completion hold captured
7 s after the CTA (ACTIVE read 0 by 17:03), so the release cell counts the delivery, not a mid-flight number.

**The phone is not the archive.** The owner cleared these test downloads afterwards (they were test captures), so
`/sdcard/Movies/DOWNI` is empty again and MediaStore holds only that directory's own row — and **no trash rows**,
so the clearing was permanent rather than a 30-day trashed delete, which is the right way to dispose of test
data. The file named in 0s-4/0s-5/0s-7 therefore exists where this pass put it, not on the phone:
`test_out/e2e_release/marvinachi-6-release.mp4` (and the debug run's `test_out/e2e/marvinachi-5.mp4`).
Re-checked on the same installed build after the clearing (2026-09-28, `MainActivity` focused, pid 20054
unchanged): `dumpsys gfxinfo` after a reset reads **0 frames / 0 janky / 1 ViewRootImpl** over 7 s — §0r-6's
rest state still holds, so nothing about the clearing touched the app.

> Sign and date here when green: **2026-09-28** — 0s-1 … 0s-10 green on both builds, and the shipped build's
> own file is byte-identical to the debug build's.

## 0t. V3.3.2 — the artefact that ships: identity, install, rest, and the chain end to end — vivo V2058

> Measured **2026-09-28**, after the version bump from 49/3.3.1 to **50/3.3.2**. Why a new version at all is
> in `CHANGELOG.md`'s v3.3.2 section: v3.3.1's tag and its published artefact predate the completion pass and
> the Part 3 UI work — its web state is 174 641 B and still loops the sweep and the shimmer — so the verified
> build ships under a new number instead of moving a published tag. **No feature was added**: this section
> checks that the thing that ships is the thing that was verified.

| # | Check | Evidence |
|---|---|---|
| 0t-1 | The build and the whole JVM suite | `:app:assembleRelease :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 35 s**, `BUILD_EXIT=0`; JUnit XMLs: **18 suites / 123 tests / 0 failures / 0 errors** — the version bump broke nothing (`_v332_build.txt`) |
| 0t-2 | Signed with the unchanged key | `apksigner verify --print-certs` → `DN: CN=Manso, O=OmniDownloader, C=US`, `SHA-256 431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5` — the same digest §0p-1 and §0r-1 recorded, so the updater's promise holds |
| 0t-3 | The version the artefact carries | read **from the artefact**: `package: name='com.omnidownloader.app' versionCode='50' versionName='3.3.2'` (`aapt dump badging`). The three spots the code comment calls "the version spots bumped at release" all moved together: `build.gradle`, `DowniEnginePlugin`'s fallback (`3.3.2`/`50L`), and `www/index.html`'s `installedVersion`/`installedVersionCode` |
| 0t-4 | The web state inside the artefact | `assets/public/index.html` **extracted from the APK** = **`0C7B0E7A16B2F86D521628CC0456BEB5D4E84F6ADC17A0E7B389FF6929D5C7C1`**, 180 230 B = **byte-identical to `www/index.html`**. It differs from §0q/§0r's audited copy (`B11770B0…`) by **exactly the two version literals** — 4 diff lines of 3 367, measured with `Compare-Object`, nothing else |
| 0t-5 | Installed in place over v3.3.1 | `adb install -r` → `Success`, no uninstall; the phone then reads `versionCode=50 versionName=3.3.2`, `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` and **no `DEBUGGABLE`** in `flags` or `privateFlags`. The grab ledger survived: Queue still carried its completed jobs before this pass's own |
| 0t-6 | The installed binary is the artefact | `pm path` → `/data/app/~~K85IKT…/base.apk`, hashed **on the device**: `sha256 cb11ce7648a8c31c4a0c00af88374268e948830ab88024f5ba14451a52eb75cb`, 44 809 989 B = the local `app-release.apk` (parked as `test_out/v332/DOWNI-v3.3.2-local.apk`) |
| 0t-7 | The release build rests | `MainActivity` focused, `dumpsys gfxinfo` after a reset + 8 s → **`Total frames rendered: 0`**, `Janky frames (legacy): 0 (0.00%)` — §0r-6's reading, still holding after the version bump |
| 0t-8 | The chain, end to end, on the artefact | a real reel typed into `#inputManualUrl` (539,1166) → `#btnInspectLink` (539,1365) → the inspector resolved it for real: `INSTAGRAM` badge, a live thumbnail, *Video by marvinachi / Marvin Achi*, and its quality lanes → **`btnConfirmDownloadQuality` inside `inspectorActionBar [0,1911][1067,2263]`** — F1's pinned row sitting **above** the 480p/audio rows it overlays → tap (534,2016) at 17:41:51 → `/sdcard/Movies/DOWNI/Video by marvinachi (7).mp4`, **3 608 924 B at 17:41** — 22 s after the tap (`test_out/v332/e2e_typed.png`, `e2e_inspector.png`, `ui_inspector.xml`) |
| 0t-9 | The output is byte-identical to the debug build's | pulled and hashed: **`DC816AC92AEEE8E0CEE25BD7F669D38A7435312602A51AEFEB186C521FC05F70`** — the same digest as §0s-5's three files; `tools\ffprobe.exe`: **h264 720×1280 @ 30 fps + aac stereo**, `duration=15.717052`, `bit_rate=1836947`, `size=3608924`, `format_name=mov,mp4,m4a,3gp,3g2,mj2` (`test_out/v332/marvinachi-7-v332.mp4`) |
| 0t-10 | The counters and the Vault, on the shipped build | Queue: `statActive 0 / statDone 5 / statSize 23` **MB GRABBED** — 4 → 5 completed and 19 → 23 MB, +3.6 ≈ the job this pass added — with the in-app `Saved ✓ / Video by marvinachi · Gallery / Movies` toast; Vault: `1 item · 3.4 MB` (the owner had cleared the earlier test downloads, §0s), first tile `Video by marvinachi (7).mp4` (`ui_queue.xml`, `ui_vault.xml`) |
| 0t-11 | **The Vault's delete path — the app's only destructive action, measured for the first time** | On this artefact: the card's own 48 px delete button (292,1918) → the **system** asks *"Allow DOWNI to delete this video?"* (`DENY` / `ALLOW`, `com.google.android.providers.media.module`) → ALLOW at 17:43:38 → the file is **gone from disk** (the folder reads `total 0`), **its MediaStore row is gone** (only the directory row remains), and the delete is **permanent — no trash row** in `content://media/external/file?includeTrashed=1`, which is also exactly what the owner's own earlier clearing left behind, so that is now explained rather than mysterious. No crash, pid unchanged (27205). **The one nit it exposed**: the Vault keeps showing the deleted tile and the "Delete requested / Confirm it in the system dialog." toast, because `deleteVaultMedia`'s `setTimeout(refreshVault, 1200)` fires while the dialog is still open and nothing re-reads after the confirm. Bounded and pre-existing (3.3.1 behaves the same): leaving the tab and returning re-reads it → `0 items · 0 MB`. Recorded, not patched — a build that had just been verified byte-for-byte is not the place for a drive-by change (`ui_delete_dialog.xml`, `ui_vault_after_delete.xml`, `ui_vault_reentered.xml`) |
| 0t-12 | What is **not** re-run here, and why | No extraction-layer or accessibility-layer code changed between §0o-b's release build and this one, so the three-platform × three-entry-point matrix is not triggered and the M1…M7 rigs are not repeated. **One honest exception:** M3's state table *is* inside this artefact and no release build has had the Core tapped on it since M3 landed at 16:19 (§0o-b's build predates it). M3 was verified on the debug build — M7's `VERDICT 0 of the C5 motion claims failed`, with the wire byte-identical — so what is untested is the release *entry* to the same code, not the code. It is a five-minute check on a real platform session and is written here as outstanding rather than implied (`V3.3.1_COMPLETION_PLAN.md` §4, F4's gate) — **closed the same evening: see §0u.** The Core was tapped on this same artefact (17:59 and 18:01:55), the idle/detected look audited repeatedly and the `PROGRESS` state measured *in flight* (`ring lit 49% of the perimeter, painted 162 deg = 45%`), with both jobs delivered into `/sdcard/Movies/DOWNI` |

**Why the version moved, in one line, for the next reader.** The tag says 3.3.1 and the published APK's web
state says 174 641 B; the verified build's says 180 230 B. Everything in §0q/§0r/§0s exists to make that
difference countable, and `CHANGELOG.md`'s v3.3.2 section carries it. The tags were left where they are: a
published tag is not moved, a new version is cut.

> Sign and date here when green: **2026-09-28** — 0t-1 … 0t-11 green on the signed v3.3.2 artefact
> (`CB11CE76…`, versionCode 50), 0t-12 recorded as the one outstanding nicety, and the local APK parked in
> `test_out/v332/` until the owner publishes.

## 0u. V3.3.2 — the Fetcher on the shipped artefact: the Core tapped for real, the ring painted in flight, and the vendor kill caught in the act — vivo V2058

> Measured **2026-09-28 17:52–18:03**, on the signed v3.3.2 artefact of §0t (`CB11CE76…`, versionCode 50,
> installed in place — nothing rebuilt for this pass), with Instagram in the front and the accessibility
> service bound. It **closes §0t-12** (M3's state table had never been reached through a *release* build's
> Core) and it caught the first live instance of the killer cell **0h-9** exists to sample. **No feature was
> added and no line of code changed**: this section taps the thing that ships.

| # | Check | Evidence |
|---|---|---|
| 0u-1 | The rig | `settings get secure enabled_accessibility_services` → `com.omnidownloader.app/com.omnidownloader.app.DowniFetcherService` (alongside TalkBack and Switch Access — the phone's normal set), `accessibility_enabled=1`; Instagram's Reels player in the front (`com.instagram.android/.activity.MainTabActivity`, `clips_tab` at (324,2242)) |
| 0u-2 | **The Core on a release build — drawn, and audited nine times** | `tools\core_state_audit.py shot … --at 452,1080 --fixed` over the live session — five shots here and the four flight shots of 0u-7: `core_over_instagram.png` → `rim rgb(25,156,166) hue=184 sat=0.85 -> teal (found on 720/720 angles)`, `mark 1472 px teal = 15.7% of the inner disc`; `core_over_reels.png` → `hue=184`, `16.1%`; `core_pre_run4.png` → `hue=186`, `13.1%`; `core_after_fetch.png` → `14.6%`; `core_after_tap.png` → `10.6%` — **every shot teal, every `bars … -> none`, every `state OK`, `VERDICT 0 mismatch(es)`**. **Why `--fixed`, and one trap:** over live video the default fit reports `only 164/360 spokes hit a rim edge` (the authored art against the baked scan bands), so `--fixed` pins the geometry (`art ring mid 53.6 px`, arc band 49.6..50.4) and every number in this section comes from it; and **downscaled copies must never be audited** — the 0.5× `_read` copies in the scratch dir report `state MISMATCH: no rim at all` purely from the resample. They are for reading, not for measuring |
| 0u-3 | The feed surface refuses honestly | A tap on the Core over Instagram's **feed** (a photo carousel, *Photo 2 of 10*) → no file, no notification, no fake progress, no crash, pid unchanged. The tree says why: `row_feed_button_share` — desc *"Send post. Button. Double tap to choose who to send this post to."*, `[461,1801][527,1928]`, **`clickable=false`** — while the neighbouring `media_option_button` (*"More actions for this post"*) is `clickable=true`. Nothing grabbable = §0n-3/§0o-b-5's `share=0` path, now measured on Instagram rather than TikTok (`ui_instagram.xml`) |
| 0u-4 | The Reach works where there *is* something to grab | Over the **Reels** player the same tap opened Instagram's own share sheet carrying the reel's link (read off the sheet: `instagram.com/reel/…?stkn=…`) with COPY / QUICK SHARE / DOWNI among its targets — the Reach's Share step, visible. The difference is in the platform's tree, not in the app: Reels exposes `direct_share_button` — desc *"Share"*, `[937,1775][1058,1896]`, **`clickable=true`** (`ui_reels.xml`, `core_reels_run2.png`) |
| 0u-5 | **vivo's ABE force-stopped the app 2.6 s into the first attempt — the killer, on the record** | The ROM's own account: `ApplicationExitInfo #1 timestamp=2026-09-28 17:55:50.647 pid=27205 reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0 importance=125 pss=263 MB rss=443 MB description=stop com.omnidownloader.app due to stop by com.vivo.abe`, with the WebView sandbox reaped 7 ms later (`#0 pid=27304 … ISOLATED NOT NEEDED`). **No crash-buffer entry and no Java exception** — a vendor kill, not a crash, and **not the user**: the tap was on the Core, never on "Force stop". This is a **seventh named instance** in this file's count (§0h-5's six of 2026-09-27 plus this one); it matches §0h-6's carried-forward shape and the Wave 0 note's *"one tap died ~2.5 s in, mid-resolver-run, before any clipboard attempt … no crash marker"* — and it is precisely the sample **0h-9** is waiting for (`dumpsys activity exit-info`) |
| 0u-6 | **The same tap on a fresh process delivers** | Relaunched 17:57:06 (pid 31309), Core re-attached (0u-2's `core_pre_run4.png`, `hue=186`), a real tap at 17:59 → on disk at **+10 s**: `/sdcard/Movies/DOWNI/Video by megaamerican.mp4`, **1 949 614 B**, `sha256 a6a5c0b710d7de746ccffc2317388fa2bec035726638d0d0c6b49ccf2fe6c430`. A poll at 2 s resolution read `files=0` until the file appeared, `pid 31309` alive at every sample: the whole §0h-4 route (Share → link → clipboard → resolve → fetch) in ~10 s, on the artefact |
| 0u-7 | **On a long flight the progress state is paintable on the artefact — §0t-12's gap, closed** | A second tap at 18:01:55 pulled a 7.3 MB reel; four shots at +4/+9/+14/+19 s → the first three read `ring … painted 0 deg = 0%` (the resolve step: nothing to paint yet), the fourth read **`ring lit 49% of the perimeter, painted 162 deg = 45%`** with `rim rgb(61,178,215) hue=194 -> teal`, `mark 14.1%`, `bars -> none`, `state OK`, `VERDICT 0 mismatch(es)` — **K-A4's "progress on the perimeter, no % text", measured in flight on a *release* build**, landing between K-A4's 88° and 182° samples. The job landed too: `Video by masii7ax.mp4`, **7 281 530 B at 18:02**, `sha256 1fc1c9e1122273eef01e361f3401e66f0631b332c35faa55dc18a18656032802` (shot parked as `test_out/v332/core_progress_45pct.png`) |
| 0u-8 | The app's own account, and the ledger | Completion notification `android.title=String (Saved to your Vault)` on channel `downi_alerts` ("DOWNI completions"); Queue **`0 ACTIVE / 6 COMPLETED / 24 MB GRABBED`** (from 0 / 5 / 23 — +1.9 MB ≈ the first grab; the 7.3 MB one landed after this read, at 18:02) with a **single** `Video by megaamerican · SAVED · Gallery / Movies` row — **one row**, so D-a's double-delivery stays fixed on the release build; Vault `1 item · 1.9 MB`, first tile `Video by megaamerican.mp4 · 1.9 MB · 28/09/2026 · Movies DOWNI`, header `🎬 1.9 MB (100%) · 🎵 0 MB (0%)`; **crash buffer 0 lines**; exit-info's newest stop is still 17:55:50, i.e. neither delivering run was killed (`ui_queue.xml` 18:00:15, `ui_vault.xml` 18:00:21) |
| 0u-9 | **The verdict, with its bound** | The Core's entry path works on the shipped artefact: drawn and audited (0u-2), refusing honestly where nothing is grabbable (0u-3), reaching a real sheet with a real link (0u-4), painting its progress (0u-7), delivering and recording (0u-6, 0u-8). **What it cannot promise is the race**: of **three** identical taps, **two delivered and one was killed by the ROM 2.6 s in** (0u-5). On this phone a grab competes with `com.vivo.abe`, so the honest form of "the Fetcher works" is the measured one — *it delivered, on the artefact, whenever the ROM let the app live*. The **rate** is what 0h-9 stays open for (F9: a longer window than any pass has had); this section records one of its samples, not the answer |

> Sign and date here when green: **2026-09-28** — 0u-1 … 0u-8 green on the signed v3.3.2 artefact
> (`CB11CE76…`, versionCode 50, installed in place, unchanged by this pass); **§0t-12 closed** by 0u-2/0u-7.
> 0u-5 is the live instance of the killer 0h-9 samples: the scheduled reading (2026-09-29 20:00 local) stays
> in place untouched. The two test grabs are left in `/sdcard/Movies/DOWNI` (1.9 MB + 7.3 MB, hashes above)
> for the owner to keep or clear. **Pushed the same evening** (§0v): `origin/main` is now `e712c1b` and the
> tag `v3.3.2` is on the remote — the release is live.

## 0v. The release that is live: what GitHub published, measured against what was verified — and tapped — vivo V2058

> Measured **2026-09-28 18:05–18:30**. The push was the owner's call this evening: `git push origin main`
> (9 commits → `e712c1b`) and `git push origin v3.3.2` (annotated → `471e3cb`). CI took the tag
> immediately — `release.yml` run **#28** (13:09:18Z, head `471e3cb`) built, signed and published
> **v3.3.2 at 13:11:08Z**, asset `DOWNI-v3.3.2.apk`. This section is what that asset *is*, measured against
> the artefact §0t/§0u verified rather than assumed.

| # | Check | Evidence |
|---|---|---|
| 0v-1 | The release exists, and the file hashed here is the file GitHub serves | `releases/tags/v3.3.2` → published **2026-09-28T13:11:08Z**, asset `DOWNI-v3.3.2.apk`, **41 996 925 B**, API digest `sha256:a97e01f764712bc83d4c4d387ec30afa7370c723c169787d76a7e2f6e1e1ba5c`; downloaded and re-hashed locally: **`a97e01f7…ba5c`, 41 996 925 B** — digest and size match, so the download *is* the published asset. Release notes come from the tagged **commit** message (`release: v3.3.2 …`, what `release.yml` reads); the annotated tag's own long message is not used |
| 0v-2 | **It is not the artefact that was verified — and here is the whole of the difference** | Verified local build `CB11CE76…` = 44 809 989 B; published `a97e01f7…` = 41 996 925 B. Both APKs hold **the same 565 entries — nothing added, nothing missing** (`lib/` equal to the byte at 17 891 132 B compressed, `res/` equal, `classes.dex` 17 B apart = build nondeterminism). The entire **2 804 750 B** of difference is one entry: `assets/chaquopy/requirements-common.imy` — **5 989 166 B locally vs 3 184 416 B in the published APK** |
| 0v-3 | The difference dissected: **the same engine, packaged differently** | Inside the two `.imy` bundles (1087 inner entries each): **the same `yt_dlp-2026.8.19` and `certifi-2026.7.22`** (`dist-info/METADATA` byte-identical at 52 224 B), every module present in both — but the local build ships **compiled `.pyc` bytecode** (`yt_dlp/extractor/lazy_extractors.pyc`, 237 752 B) where CI ships **the `.py` source** (`…/lazy_extractors.py`, 135 092 B), module for module, and that is the whole gap. Functionally the same yt-dlp (CPython compiles at import; first import marginally slower). Written down so "the published APK is 2.8 MB smaller" never has to be a mystery: it is packaging, not a missing extractor |
| 0v-4 | What the published artefact **does** carry, checked on the file itself | `apksigner verify --print-certs` → `DN: CN=Manso, O=OmniDownloader, C=US`, `SHA-256 431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5` = **the unchanged key** (§0p-1, §0r-1, §0t-2), so it installs in place over v3.3.1/3.3.2; `aapt dump badging` → `versionCode='50' versionName='3.3.2'`, no `debuggable`; and `assets/public/index.html` **extracted from the published APK** = **`0C7B0E7A16B2F86D521628CC0456BEB5D4E84F6ADC17A0E7B389FF6929D5C7C1`**, 180 230 B = **byte-identical to §0t-4's verified web state** — the only commits after the tag are documentation, so the UI in the download is the UI that was verified |
| 0v-5 | The published binary, installed and identified **on the device** | The phone came back on ADB at ~18:16; `adb install -r` of the downloaded asset → **Success**, in place over the verified build (no uninstall, data kept). `pm path` → the new `base.apk` hashed **on the device** = **`a97e01f764712bc83d4c4d387ec30afa7370c723c169787d76a7e2f6e1e1ba5c`** = the published asset, byte for byte; `dumpsys package` → `versionCode=50`, `versionName=3.3.2`, `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` (**no `DEBUGGABLE`**), `lastUpdateTime=2026-09-28 18:16:32`. The accessibility binding survived the swap (`settings get secure enabled_accessibility_services` → `…DowniFetcherService`, and `dumpsys accessibility` lists it under *Bound services*), the service came back as a foreground service (`downi_fetcher`), and the grab ledger survived (three files already in the folder) |
| 0v-6 | The Core, drawn on the published build | Its overlay window is real and on screen: `Window{com.omnidownloader.app:e29aed0 … ty=2032}`, `mAttrs={(904,711)(176x176)}`, `frame=[904,711][1080,887]`, `mHasSurface=true`, `mViewVisibility=0x0`, `isVisible=true`, `mDrawState=HAS_DRAWN`. Inside it a full-frame pixel scan (hue 170–205, sat ≥ 0.5) finds **422 teal pixels in a 110×110 px box** at (936,744)–(1046,854) — the verified Core's own signature size (its §0u mark bbox was 109×109) — and the crop is the authored art: teal rim, dark glass body, teal mark (`_v332_core_crop.png`, `core_published_at992.png`). **Two honest notes:** the position is *not* §0u's (452,1080) — the fresh install re-placed the Core at (991,799) — and at that spot `--fixed`'s baked rim band reads washed out over Instagram's bright sky (`rim rgb(158,182,209) sat=0.24`, `found on 363/720 angles`, `state OK`, `VERDICT 0 mismatch(es)`, and a first attempt at the stale (452,1080) read only video). The audit's fixed geometry is tuned to §0u's dark background, so **the visual and the teal bbox are the evidence here, not the rim band** |
| 0v-7 | **One real tap on the published binary delivers** | 18:20:53 — one tap at the Core's own centre (991,799) over a Reels player (`clips_viewer_container`, *Reel by didembalcin*, `MainTabActivity` focused) → **`/sdcard/Movies/DOWNI/Video by didembalcin.mp4`, 2 500 277 B at 18:21**, pulled and hashed here: **`sha256 6e5346a51de3ee4b1b052009bec2c6ba8f89d663fc6fc1c92f9bb41e83298f99`**. The completion notification posted at **18:21:06** (`id=5812`, channel `downi_alerts` "DOWNI completions", importance 3, `android.title=String (Saved to your Vault)`, the app's teal accent `0xff22d3ee`), and the app's own Queue read straight after: **`0 ACTIVE / 10 COMPLETED / 36 MB GRABBED`** with `Video by didembalcin · Gallery / Movies · 28/09/2026` as the newest row (`ui_published_queue2.xml`, `published-didembalcin.mp4`) |
| 0v-8 | No kill, no crash, on the published build | `pid 8152` alive before, during and after the tap; crash buffer **0 lines**; and the exit ledger read *with its reasons*: **`#1 … reason=10 subreason=21 … description=stop com.omnidownloader.app due to installPackageLI`** = the install replacing the process at 18:16:32 (not a kill), **`#3 … description=stop com.omnidownloader.app due to stop by com.vivo.abe`** = the 17:55:50 vendor kill of §0u-5. **The ABE did not touch the published build's run.** One observed-but-unattributed item: a rose alert on `downi_alerts` titled **"Sign-in required"** (`when` = 18:14:44, i.e. before this tap and during the owner's own use) — recorded as seen, body not captured; whether that was a real login wall or a stray warning is the owner's to say |
| 0v-9 | The ledger's growth across the window, for honesty's sake | Between §0u-8's read (18:00:15, verified build: `0/6/24`) and this one (`0/10/36`) the ledger gained **four** completed grabs: **mine** (didembalcin, 2.5 MB) and **three from the owner's own use** — including `Video by bollydhoom4u`, which the app's own Grab screen labels *"Last: Video by bollydhoom4u re-grab"*. Not my measurements and not counted as such, but real grabs by a real user on a real build, which is worth more than any rig in this file |

> Sign and date here when green: **2026-09-28 18:16–18:22** — 0v-1 … 0v-8 green. The published artefact
> (`a97e01f7…`, 41 996 925 B) was installed in place over the verified build, hashed **on the device** to
> itself, drawn (0v-6), and **tapped once — and it delivered** (2 500 277 B, `6e5346a5…`, the notification
> at 18:21:06, the ledger row on top, no kill, no crash). That closes what §0v-5 opened: the file on GitHub
> is now a build that has been *run on the phone*, not merely one that matches the one that was. The rose
> "Sign-in required" alert noted in 0v-8 is the only loose end — seen, not attributed.

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
| C4 | `CHAIN_TARGET_CLICK ok=true`; EITHER the sheet route lands a grab (notification) OR the Copy-link route puts the deep link on the clipboard (owner-verified) | ⚠️ **latest tap-only run PASS; reliability sample still open** | Historical fixed-build rate was 2/7 before D-e's repair. On the clean 18:33 build with `handoff=false`, one physical TikTok tap reached a real URL: tries 1–4 `focus=false, got=null`, try 5 focus arrived, try 6 `got=yes`, then `CHAIN_DELIVER_OK route=clipboard tap=1`. This confirms the mechanism and fix, not yet a statistically reliable C4 rate. Engine reached 91% before the separate vivo ABE kill |
| C5 | End-to-end **TikTok median ≤ ~4 s** (cell revised by ruling R3, 2026-09-26 — the old "≤ ~3 s" target was aspirational and is **not** claimed) | ✅ **PASS (revised cell)** | post-Wave-1 TikTok clipboard set **3.25 / 3.62 / 4.37 s** (median **3.6 s**); the earlier 8-tap set **3.2–5.9 s** (median **4.1 s**, ≈4.2 s avg) sits at the boundary. Further optimization stays a measured follow-up (`postStep 1300 ms`, sheet setup, scroll wait) |
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
| K-B1…B5 | Press/drag/snap/position/drag≠tap in the **Core** | ✅ **ACCEPTED on device 2026-09-25** (release commit 8c9577a): one tap → exactly `1 × CORE_TOUCH down/up → CORE_TAP → CHAIN_DELIVER_OK`; 1500 ms drag → `dragging=true` + `CORE_MOVED`, no tap; position survives hide/show and a full rebind (`[236,1653][412,1829]`) |
| K-C1…C5 | `PlatformProfile` drives WAKE/DETECTED honestly; never downloads | — | **BUILT + DEVICE-VERIFIED 2026-09-25 22:22–22:32** (vivo V2058, Instagram Reels): watching signals from the Phase 0 dumps (`clips_media_component` …) drove `CORE_DETECT detected=true` → `CORE_STATE wake` → `detected` on a live Reel within 4 s of session start — twice, on two fresh binds; screenshot `test_out/core_detected_live.png` shows the lit perimeter + sheet-2 mark over the Reel. Detection still never downloads. The off-video idle flip is the same code path with the inverse flag (not separately captured — the vivo ABE killer took the process before a profile-page check; residual, low risk) |
| K-D1…D5 | Real progress, duplicate check before tap, PAUSED behind D3 | — | **BUILT + DEVICE-VERIFIED 22:32** — the full living-Core cycle on one tap: `CORE_TAP` → `CHAIN_DELIVER_OK reel/DdsD3R9sejO` → `CORE_JOB progress pct=1` → **75→79→82→87→91→97%** (the real engine's numbers, one snapshot per 800 ms write) → `CORE_JOB completing pct=100` → calm `idle` after the 20 s TTL; the file landed in `Movies/DOWNI` and `test_out/core_progress_live.png` shows the bright full perimeter after completion. Duplicate adoption + already-saved refusal are in `deliverByTap` (running same-URL job → `CHAIN_DELIVER_ADOPT`; fresh done row → `suppressed=already_saved`). PAUSED stays **D3-gated**: real pause/resume needs engine surgery (`.part` continuation — tiktok direct opens `'wb'`, yt-dlp runs `nopart:True`) which The Law reserves for the full device matrix; the visual states exist, nothing fake drives them |
| K-E1…E5 | Settings card, persistence, honest status, exemption walkthrough | — | Phase E not implemented (no Fetcher UI in `www/index.html`) |
| K-F1…F5 | Rotation, lock screen, app switch, network, low memory, FGS, reboot | — | Phase F not started (this matrix is its first artefact) |
| K-G | Spike out of the release source set; debug channels removed; on-device logs deleted | — | ✅ **DONE 2026-09-26 (Phase G):** `FetchSpikeService` renamed `DowniFetcherService` (manifest label now "DOWNI Fetcher"), legacy `DowniBubble` deleted, bench channels (`core.cmd`/`chain.cmd`), the forensic file log, screenshots and the handoff gate are all `BuildConfig.DEBUG`-gated — a release build carries none of them. On-device spike logs deleted at release. Rename-safe re-arm: stale component tokens are filtered when the self-recovery or the settings toggle rewrites the binding |

**Defects found while closing C1–C5 (2026-09-25, on the current build):**

| # | Defect | Evidence | Where the fix belongs |
|---|---|---|---|
| D-a | **One tap can grab the same video twice** — the spike's own `pipeline(url)` handoff *and* the chooser click both fire for one URL | `Movies/DOWNI`: `fliqr.clips.mp4` + `fliqr.clips (1).mp4` both **2,135,039 B**; `rekrobot.mp4` + `rekrobot (1).mp4` both **3,377,818 B** | ✅ **FIXED 2026-09-25 16:47**, verified live 16:51 (tap via `input tap 961 798`): one `CHAIN_DELIVER route=clipboard` for the run, no second claim; routes now stand down (`CHAIN_CLIP_SUPPRESSED`), and the dump path skips during a run (`PIPELINE_SKIPPED chain_running`). Phase D still owns the production version of this rule |
| D-b | **Chain can resolve a non-video URL** (a profile/bio link) | 11:06:02 `PIPELINE_HANDOFF_OK url=https://fikrfreeapp.onelink.me/xoBT/tdnrp3bc` | ✅ **FIXED 2026-09-25 16:53**: pure rule `fetcher/MediaUrl` + `MediaUrlTest` (**7 tests**; suite **26/0**). Rejects `onelink.me` (that exact link), profile paths, `linktr.ee`, YouTube; flags TikTok photo posts as `tt_photo_post`. Live 16:54: a real share link passed and delivered once |
| D-c | **C5 latency cell revised (R3, owner 2026-09-26)** — the ≤3 s target was aspirational; the honest cell is "TikTok median ≤ ~4 s" | 8 `BUBBLE_TAP` → `PIPELINE_HANDOFF_OK` pairs 16:30–16:35 (3.2–5.9 s, median 4.1 s) + the post-Wave-1 TikTok clipboard set 3.25/3.62/4.37 s (median 3.6 s) | **CLOSED as revised.** No surface claims the aspirational ≤3 s; further optimization is a measured follow-up, not a spec |
| D-d | A chooser walk can read **quick-settings rows** instead of share targets | 16:33:51 `CHAIN_CHOOSER_BUTTON on wi-fi,cmcc-fiber … off torch … silent` | ✅ **FIXED 2026-09-25 17:02**, verified live all three walks: `pickShareRoot()` skips `com.android.systemui` (`… _WINDOW_SKIPPED pkg=com.android.systemui why=shade_cannot_hold_share_targets`) and chooses deliberately (`… _ROOT which=platform_app`). The walk no longer reads quick settings |
| D-e | **The copy-link route is unreliable** — the sheet's Copy link is clicked but the clipboard read comes back empty, so the run delivers nothing | `CHAIN_CLIPBOARD got=null` at **17:02:05.631**, **17:12:05.631** and **18:03:34.307**; same pre-fix build delivered at 16:51/16:54. Cause isolated at 18:05: `setFocusable(true)` did not give DOWNI window focus | ✅ **FIXED + VERIFIED 2026-09-25 18:33–18:34.** `DowniBubble.requestFocus()/hasWindowFocus()` now report real focus, and `readClipAndPipe` retries six times at 250 ms. Physical proof with the gate off: tries 1–4 `focus=false, got=null`; try 5 `focus=true, got=null`; try 6 `focus=true, got=yes`; then `CHAIN_DELIVER_OK … tap=1`. A larger C4 reliability sample is still required |
| D-f | **A miss ends the run with nothing** — no fallback when the chooser does not appear, and no retry while the sheet animates | IG 17:05: `CHAIN_TARGET_CLICK which=chooser_row` → no `android` chooser window → 4 scrolls → `CHAIN_CHOOSER_NO_DOWNI`, and **no copy-link fallback** though a clickable `copy link` row was found at 17:05:06.139. TikTok 17:04: `CHAIN_NO_TARGET neither DOWNI nor Copy link found` | **SUPERSEDED by the owner's 18:20 ruling.** The chooser/Drop route was deleted from the Fetcher. The current resolver clicks only the platform's **Copy link**, scrolls the platform sheet up to two times, then uses the focus-aware six-attempt clipboard read verified in row D-e. A platform-specific tree route remains evidence for Phase C, not a user-facing choice |
| D-h | **A run can "claim" a delivery it never made** — found by reading the code, not by log (2026-09-25 18:0x) | `extractAndVerdict` claimed `dump_of_sheet` before `pipeline(url)`, and the old clipboard route could similarly suppress a real run while the bench gate was off | ✅ **FIXED 2026-09-25 18:06 and superseded by the owner ruling at 18:20.** Automatic bench handoff remains gate-controlled; the explicit Core-tap path is intentionally independent of that bench switch and delivers through `deliverByTap()`. Physical proof: gate off, one tap, exactly one `CHAIN_DELIVER route=clipboard` / `CHAIN_DELIVER_OK … tap=1`; D-a's one-delivery claim still suppresses later routes. Suite **26/26, 0 failures** |
| D-g | **Suspect: an IG `/p/` carousel post is accepted as a video** | 16:33:50 `STEP3_DUMP_37 confidence=HIGH source=tree url=https://www.instagram.com/p/DdsM_GHEXAE/?img_index=14` → handed to the pipeline. `img_index=` marks a carousel slide, and `MediaUrl` accepts any `/p/<code>` | **Ruling needed:** either accept (photos are future work, `ROADMAP.md`) or flag `ig_photo_post` the way TikTok photo posts already are. `/reel/` is unambiguously video |

**Defects found by the pre-tag forensic audit (2026-09-25 — found by reading the code; none had ever fired in a captured log):**

| # | Defect | Evidence / mechanism | Where the fix belongs |
|---|---|---|---|
| D-i | **A second tap inside the first run's clipboard window races the stale reader.** `chainStep3` posts the clipboard attempts and the focus release, then calls `chainReset()` — so `chainRunning=false` and `chainDelivered=false` while up to `500 + 6×250 ms` of delivery machinery is still pending. A tap in that window started a new run beside run 1's armed reader: it could deliver run 1's (stale) clipboard URL for the new tap, claim the budget and suppress the real delivery, or share `clipTries` with the live reader | Audit 2026-09-25, code reading; no captured log — no device session ever tapped twice inside 2.4 s | ✅ **FIXED + DEVICE-VERIFIED 21:32 and 21:38.** Two rapid Core taps on two different videos (4.8 s apart, scroll between): `CHAIN_DELIVER_OK … ZSbNYgraB` then `CHAIN_DELIVER_OK … ZSbNYp3Nu` — each tap resolved the video actually on screen, one delivery each, no stale delivery, no suppression, no duplicates; repeated at 21:38/21:39 (two taps 7.5 s apart, TikTok's feed auto-advanced between) → again two different videos, both delivered once. A tap during a dead session logged `CORE_TAP_NO_SESSION` (honesty guard), and one early tap inside run 1's active window logged `CORE_TAP_BUSY`-class behaviour per B4 |
| D-j | **Bench `chain.cmd click` runs bypassed the run discipline** — `pollChainCmd` never set `chainRunning`, so the dump path treated the run as "not running": with the bench gate armed it delivered without claiming while the clipboard route claimed separately — the original D-a duplicate shape, still reachable in bench mode | Audit 2026-09-25, code reading (`extractAndVerdict`'s `chainRunning` branch was the dump path's only claimant) | ✅ **FIXED.** `pollChainCmd` click runs now go through `beginChainRun()` — busy guard, one-delivery budget, generation, interactive flag — identical to a tap run. Product taps were never affected (the gate ships off) |
| D-k | **Rapid retap = duplicate download.** Two taps on the same video seconds apart resolved the same clipboard URL twice; `startShared` has no URL-level dedup (the engine is not modified, ruling 4), so two identical jobs ran and the Vault saved `Video (1).mp4` beside `Video.mp4` | Sheet 8 §5 "URL matching — prevents resubmitting job" had no implementation before Phase D; `chainDelivered` only guards inside one run | ✅ **FIXED (narrow scope).** Pure `fetcher/DeliveryGuard` (clock-injected, 6 JVM tests): the same URL may not re-deliver within 8 s (`CHAIN_DELIVER_DUP suppressed=same_url_within_8s`). Honest limits: the suppression path has JVM-only proof — the 21:38 device attempt couldn't pin it because TikTok's feed auto-advanced between the taps (both videos legitimately delivered); TikTok short codes are stable per video (the 16:51/16:54 logs share one link) and IG's copy link is canonical, so a same-video retap does produce the identical string the guard catches. Full job-liveness/file-hash dedup stays with Phase D's `CoreJobBinding` |
| D-l | **Rotation could strand the Core off-screen.** Clamping ran only in `show()`/`moveTo()`; a shown Core survived a rotation with coordinates beyond the new bounds — half or wholly off-screen and untouchable until the user left the app | Cell B5 specifies "clamped, never half off-screen", but its rotation path was uncovered (B5 itself has never been run on the phone) | ✅ **FIXED + DEVICE-CHECKED 21:40.** `DowniCore.ensureOnScreen()` re-clamps a shown Core from the existing 900 ms `coreTick`; the clamped position is deliberately not persisted, so rotating back restores where the user left it. On-device: `user_rotation=1` under TikTok left the display at `ROTATION_0` — TikTok (and IG) are portrait-locked, so the rotation scenario structurally cannot occur under the Fetcher's target apps on this device; the guard ran and verified no-op-safe (the Core held its remembered `[236,1653]` position, no spurious moves). Foldable / split-screen rotation proof remains open and is not reachable on the V2058 |
| D-m | **A rebind without `onDestroy` doubled every poller.** `onServiceConnected` unconditionally re-posted the four loops (800 ms core.cmd, 900 ms core tick, 1.5 s chain.cmd, 30 s heartbeat); this ROM is documented to rebind/wipe `enabled_accessibility_services` on its own mid-run | Audit 2026-09-25, code reading; benign but doubles polling, log volume and battery after each rebind | ✅ **FIXED.** `pollersArmed` posts the loops once per service instance; `onDestroy` resets it, so a fresh bind after a real destroy still re-arms |
| D-n | **A rebind without `onDestroy` also leaked a second log-writer thread.** `startWriter()` ran unconditionally in `onServiceConnected`, so a clean rebind started another writer draining the same queue (two threads racing one file); exposed by the Wave 0 extraction of the log plumbing | Audit 2026-09-26 (Wave 0), code reading | ✅ **FIXED.** `FetcherBench.openLog()` reuses a living writer (`BENCH_LOG_REUSED rebind=1`) and resets the byte counter only on a fresh open |
| D-o | **`chainReset()` painted a hardcoded IDLE over the arbiter's truth.** Every path that reset right after `resolverFailed()` (ledger rejection, `no_share_row`, `no_copy_link`) erased the FAILED hold within one frame — the documented 3-second "something went wrong" moment never rendered on those paths — and a delivered job's PROGRESS flickered to IDLE until the next binding poll | Audit 2026-09-26 (Wave 0), code reading; visible consequence of commit 47c2b45's arbiter contract | ✅ **FIXED + DEVICE-CHECKED 2026-09-26.** `chainReset()` applies `CoreArbiter.baseState()` (job > resolving > detection > idle); `beginChainRun()` applies it too, so RESOLVING renders during a run for the first time. Device gate on the vivo V2058 after install: `CORE_CMD show` → `CORE_ATTACH` → `CORE_STATE wake` → `CORE_STATE progress` + `CORE_PROGRESS 60` → `CORE_STATE paused` → `CORE_HIDE`, heartbeat + FGS normal, `test_out/wave0_state_progress60.jpg` shows the 60% perimeter |
| D-r | **"The link copies and the reel scrolled" (owner report, 2026-09-26).** During a run the Core went NOT_TOUCHABLE for the whole ~3.5 s, so the owner's second tap or a stray brush landed on the FEED — where a little vertical movement is the next-reel drag. Two further feed-touching hazards shared the root cause: the sheet-scroll swipe used fixed SCREEN fractions (fall-through risk), and chainStep3's BACK press fired even when the sheet had auto-dismissed (BACK then hit the feed) | Owner report + log forensics: zero `CHAIN_SHEET_SCROLL` in today's logs (the swipe never even fired for the captured runs — the shield gap was the live mechanism), `route=gesture` copy-link clicks confirmed | ✅ **FIXED + DEVICE-VERIFIED 18:49 run.** (1) The Core now SHIELDS during a run: touchable, absorbing every touch aimed at it, acting on none (`CORE_SHIELDED true/false`); (2) touchability drops only for the flight of each injected gesture, restored from the gesture's own completion callback (`dispatchGestureGuarded`); (3) sheet scrolling goes through the sheet's own scrollable node (`ACTION_SCROLL_FORWARD`, coordinate-free) with the gesture fallback clamped INSIDE the sheet window's bounds (`fetcher/SheetSwipe`, 6 JVM tests — the fixed-fraction swipe is deleted); (4) both BACK presses are guarded by `shareSurfaceOpen()` (`CHAIN_CLOSE_PANEL skipped already_closed=1`). Verification run: tap → `CORE_SHIELDED true` → full chain → `CORE_SHIELDED false` → `CHAIN_DELIVER_OK` → `RUN_END delivered=true ms=3069`, **no feed scroll, no `CORE_LIVE_HIDE`** |
| D-q | **The Core hid mid-clipboard-window.** The Wave 1 focus pre-warm makes DOWNI's own overlay window the active one; `coreTick` read that as "left the target app" and hid the Core (device log 18:42:48: `CORE_LIVE_HIDE pkg=com.omnidownloader.app` 1.8 s before the clipboard read landed) | Found in the 18:42 device log while verifying D-r | ✅ **FIXED + DEVICE-VERIFIED.** `DowniCore.isFocusableNow()` lets `coreTick` distinguish the deliberate focus window (Core holds position) from the user opening DOWNI (Core hides, unchanged). 18:49 run: no `CORE_LIVE_HIDE` between `panel_closed` and `CHAIN_DELIVER_OK` |

**Wave 0 device pass (2026-09-26, vivo V2058, build = main @ ca70127 + waves, prod-signed debug install):** install over the prod build succeeded (signing continuity); self-recovery re-armed the binding after both an install wipe and a live vivo ABE kill; the FetcherBench log pipeline works end to end (`SERVICE_CONNECTED … handoff=false`, new production banner); the bench command channel drives the Core through the refactored arbiter path (states above); screen-off discipline held (log silent while the screen was dark); heap 5–6 MB of 256. Owed to the owner's eyes: the N8–N13 truth re-checks in-app and a 30-minute soak with normal use.

**Wave 1 device session (2026-09-26, vivo V2058, TikTok + Instagram, prod-signed debug install of commits 9314f00…):**
- **The narrated run works end to end (the observer's beats, three real deliveries):** `RUN_START platform=tiktok plan=sheet_tree>copy_link` → `RUN_STEP share_found` → `sheet_open after_ms=550` → `copy_link_clicked` → `panel_closed` → `RUN_CAPTURE route=clipboard` → `CHAIN_DELIVER_OK` → `RUN_END delivered=true route=clipboard`. Route attribution C1 re-checked via `chain.cmd dry` (`share=3`, `CHAIN_DRY_DONE`).
- **Latency samples (tap → delivery):** TikTok clipboard route **3.25 s / 3.62 s / 4.37 s** (median 3.6 s; historical average 4.2 s) — the event-driven waits plus the Wave 1 focus-overlap are trending better, n too small to conclude. Instagram baseline from the owner's own tap on the pre-Wave-1 build: **3.35 s** (17:14:33.087 → 17:14:36.439, attempt-1 clipboard read). **C5: the owner revised the cell (ruling R3, 2026-09-26) to "TikTok median ≤ ~4 s"** — this recent set (median 3.6 s) meets it; the 8-tap set (median 4.1 s) sits at the boundary. The aspirational ≤3 s is retired and is not claimed anywhere.
- **RUN_END honesty fix (found live in the 17:53 run):** the first narrated run declared `RUN_END delivered=false ms=3447` and the delivery landed 2.2 s later — the run's end was declared while D-i's async clipboard window was still pending. Now `chainReset` marks the end pending and the clipboard attempts' terminal branch fires `RUN_END` with the true outcome (`delivered=true route=clipboard`).
- **Route vocabulary normalized at the ledger:** the strategy ledger records `tiktok/copy_link` (the ROUTE), while logs keep the historical `route=clipboard` (the MECHANISM). Device log: `STRATEGY tiktok clipboard 100% (2/2)` under the old build → `copy_link` keys on the final build; `routeHealth()` feeds `fetcherStatus.routes` for the Wave 3 card.
- **The vivo killer struck mid-experiment twice** (one tap died ~2.5 s in, mid-resolver-run, before any clipboard attempt; log ends abruptly, no crash marker — the documented ABE shape). Self-recovery restored the Fetcher each time. The user-side exemption walkthrough (Fetcher settings card) remains the gating lever for stable measurement sessions.

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

