# DOWNI Changelog

Full release notes + signed APKs live on
[GitHub Releases](https://github.com/MansoorjAhmad/Downi/releases).

## V3.3.0 — Fetcher 2.0 ("Core" redesign) — in progress, not shipped

**Milestone 1 — the Core is the owner's own art, not a re-drawing of it.** Fetcher 1.0's
procedural pebble is *deleted*, not retuned: the lobed body path, the body/bounce/gloss shaders,
the glass arc, the stroked gel rim, the separate mark bitmap and the drawn pause bars are all gone
from `CoreHost`, which now composes one `drawBitmap` per state with the bloom, the ring and the
resolver orbit. The material is the sheet's orb, lifted out of the owner's C1 by the new
`tools/core_sheet_extract.py` — `core_orb.png` (224 737 B) and `core_orb_paused.png` (152 993 B),
512 px RGBA, with the studio drop shadow and every annotation hairline gated out of alpha
(colourless = shadow, saturated-or-bright = the orb's own light; the tool's own stray-island check
reports 0). Geometry is measured rather than derived: `CoreLook.ART_TILE_RATIO = 0.8606` and
`_PAUSED = 0.7799`, so `side × ratio` still equals the exact 2r Fetcher 1.0 drew at 48, 56 and 64 dp
— the material changed, the size did not. The state colour shift is the pure, unit-tested `CoreTint`
(hue rotation + desaturation, Rec. 709, no red anywhere).

**The ten state animations exist — authored by the generator, no After Effects needed.**
`tools/core_lottie_build.py` writes all ten files of the v3.3 plan (§3) as real bodymovin JSON at
512 px / 60 fps, with the lengths the design asks for (90/36/120/24/120/18/45/30/36/30 frames), from
a declarative state table — art, scale, rotation, opacity, cross-fade, ring and merge keyframes —
over the sheet art. The ring is real vector geometry (an ellipse stroke with an animated trim,
0 → 100 % linear, starting at 12 o'clock) so `core_progress.json` is scrubbed by `setProgress`; the
comet head travels with it as a second trimmed arc; the C5 completing merge, the C3 press/rebound
and the C6 rose → teal retry are all in the files. The two looks the sheets never exported
separately — C6's muted rose and the muted blue/grey — are baked by the same generator using
`CoreTint`'s own numbers, which it reads out of `CoreTint.java` and refuses to drift from.

Verified: `python tools/core_lottie_build.py --check` → **10/10 states built and validated** (canvas,
frame rate, `op` == frames, every image refId resolves to a 512 tile, keyframe times monotonic and
inside the composition, no absolute path in any file, every state animates, and no 1-key
"animations"), plus a five-frame QA contact sheet per state in `test_out/lottie_qa/`. The Java side
is `CoreLottieSpecTest` (**5 tests**, with Gson added as a test-only dependency) so the contract is
re-checked on every build → suite **103 tests / 0 failures**. `assembleDebug` + `tools/sign_spike.ps1`
= BUILD SUCCESSFUL; `app-spike-signed.apk` is 46 084 426 B, prod-signed (`4311317…`), SHA-256
`456938d2…`, and now packages `assets/core/*.json` + `assets/core/images/`.

**Milestone 1 is verified on the phone, not just in the build — and the phone rewrote the harness.**
On the vivo V2058 the Core draws the owner's art in all thirteen states
(`CORE_ATTACH type=2032 x=452 y=1080 size=176px size_dp=64 touchable=1`, 24 shots in
`test_out/core_visual`), and the state is measurable in the pixels: the teal mark is 1153 px in idle,
1345 px across wake/detected/pressed/dragging/snapped, 1397 px in resuming, 1305–1316 px in
completing/complete, and 857 px in PAUSED — the two-bar art, not the chevron. The disc annulus reads
hue 188° (idle), 188° (detected), 191° (paused) and **342° rose in FAILED only** (sat 0.29), while the
two baked tiles themselves measure 201° and 204° — so no tile ships rose, and the rose really is the
FAILED tint. Annulus value tracks the energy channel (0.27 idle → 0.30 detected → 0.25 paused), i.e.
"the energy receded" survives being a bitmap instead of being drawn.

Getting there cost four empty runs. The causes are now in `DEVICE_TEST.md` §0 and fixed in the new
`tools/core_shots_live.ps1`: this ROM unbinds `FetchSpikeService` by itself ~40 s after a bind (the
driver re-launches to re-bind and checks liveness before every shot — the 24-shot pass then finished
in 50 s with `SERVICE_UNBIND=0`); arming by hand with `settings delete` + `put` looks healthy
(`SERVICE_CONNECTED`, `CORE_READY`, `CORE_ATTACH`) and kills the fresh bind ~2 s later while `pidof`
and `settings get` still report healthy; `at x y` must be pushed *after* `show` because `show()`
re-reads the persisted position; and `tools/core_state_audit.py` gained `--fixed`, because its disc
fit is tuned to a stroked rim and had reported "no Core to measure" for 14 of 17 shots that plainly
had one.

**Milestone 2 — the Core's motion is the sheet's own animation, running on the phone.** The ten
authored files no longer sit unused in the APK: `CoreLottie` is the one table mapping a state to its
file (`core_wake`, `core_idle_ready`, `core_press`, `core_progress`, `core_pause`, `core_complete`,
`core_failure`/`core_unsupported`; `core_dormant` and `core_retry` shipped but intentionally
unwired), and `CoreHost` draws the current state's composition through a `LottieDrawable` inside its
own canvas — no child view, because the Core's touch handling is the part of Fetcher 2.0 that must
not change. The JSON's image layers resolve through an `ImageAssetDelegate` over the *existing*
`drawable-nodpi` tiles, so the material still lives in exactly one place; `core_progress` is never
played but scrubbed with the real fraction; READY is the only file that loops; and a composition
that is missing or refused falls back to the exact static art the M1 gate measured — **the
animation is never the only path to a Core**. Pinned on purpose: `com.airbnb.android:lottie:6.6.10`.

Verified on the vivo V2058 (`tools\core_shots_live.ps1`, 24 shots in `test_out\core_visual_m2`,
51.7 s, `CORE_ATTACH=2`, `SERVICE_UNBIND=0`): every wired state reports its file on the `CORE_STATE`
log line — `detected stage=core_idle_ready` · `wake stage=core_wake` · `pressed stage=core_press` ·
`paused stage=core_pause` · `complete stage=core_complete` · `failed stage=core_failure` ·
`progress stage=core_progress` — while idle / dragging / snapped / resuming / completing stay
`stage=null` on the static art, and no line carries an `err=`. Suite: **111 tests / 0 failures**
(`CoreLottieWiringTest` pins the table against the shipped JSON).

**Then that pass caught the bug that actually mattered: every file loaded and drew nothing.** The
twenty-four shots were honest about it — `core_complete` reported `n=3` layers and hundreds of draws
while the window stayed empty, and the new draw-time dump said why:
`tree=[ShapeLayer:visible=false ... ImageLayer:visible=false/core/core_orb.png]`. The cause was in
the files, not the app: python-lottie only copies `ip`/`op` onto a layer through its own add-layer
path, and this generator appends straight to `an.layers`, so every layer shipped without them.
lottie-android then substitutes the composition's *end* frame for the layer's out point, which puts
the layer's in/out keyframe exactly on progress 1.0 — the frame a settled, non-looping state holds —
and `BaseLayer.setVisible(false)` hides it. Every gate shot is taken in precisely that state.
`tools\core_lottie_build.py` now writes `ip = 0` / `op = <composition op>` on every layer and its
`--check` refuses a file without them, and `CoreLottieSpecTest` re-asserts it on every build
(`everyLayerCarriesTheCompositionInOutPoint`).

Verified the same way, end to end: the ten assets read back out of `app-spike-signed.apk` itself all
carry the layer `ip`/`op` (10/10), the device log flips to
`tree=[ImageLayer:visible=true/core/core_orb.png]` with the state held, and re-running the identical
24-shot pass into `test_out\core_visual_m2b` (61.9 s, `CORE_ATTACH=1`, `SERVICE_UNBIND=0`, the same
452,1080 geometry) measures it in the pixels. Suite: **112 tests / 0 failures** — the run that
includes the new `ip`/`op` assertion.

**What the M2 gate found, fixed and gated (all in this release):**
1. **All ten files were unloadable on the device.** python-lottie's `Color` takes its components
   positionally, and `objects.Color(colour)` with the tuple as one argument emitted
   `[[r,g,b],0,0,1]`; lottie-android refused every file with `JsonDataException: Expected
   BEGIN_OBJECT but was BEGIN_ARRAY at $.layers[0].shapes[0].it[1].c.k[0]`, and every state fell
   back to static — silently, because logcat is filtered for this app on this ROM.
   `tools\core_lottie_build.py` now calls `Color(*colour)`; its `--check` asserts that a stroke/fill
   colour is four flat components, and `CoreLottieSpecTest` asserts the same on every build.
2. **Every state settled to an empty Core.** All ten files loaded and drew nothing: the layers
   carried no `ip`/`op`, so lottie-android gave them the composition's end frame for an out point,
   the in/out keyframe landed on progress 1.0, and every settled state hid itself. The generator
   writes both fields now and refuses to build a file without them; `CoreLottieSpecTest` asserts it
   on every layer of every state, and the fix is read back out of the shipped APK (10/10 files) and
   off the phone (`ImageLayer:visible=true` where the pre-fix log said `false`).
3. **A silent fallback must say why.** `CoreHost` reports the refusal reason on the same
   `CORE_STATE` line (`stage=null err=<file>: <cause>`) — the datum that caught (1), which no
   phone screenshot could ever show.
4. **Two command races in the harness.** The service's `core.cmd` poller reads at ~2 s intervals,
   so the driver's `show` (600 ms before `at`) and later `state progress` (1.8 s before
   `progress 0`) were overwritten before the poller saw them — the Core stayed hidden for a whole
   pass (`CORE_ATTACH=0`, every state honestly logging `stage=null`), and the ring shots showed the
   previous look. `tools\core_shots_live.ps1` now pushes multi-line bodies in one write, and
   `tools\core_review_sheets.ps1` gained `-Dir` so strips can be built for the pass being reviewed.

**The Core audit reads the new Core — bands re-derived, not loosened.** `tools\core_state_audit.py`
was calibrated to Fetcher 1.0's procedural rim: it scanned radius 64.2..65.6 px (arc) and 66.8..70.7
(rim) at 64 dp, while the baked Core's own light ends at ~57 px and the app behind starts at ~60 — so
every state of the M1/M2 passes reported `MISMATCH: no rim at all` and the ring read 0 %, sixteen
mismatches a pass on shots that were plainly correct. The geometry is measured now: a circle fitted
to the closed ring of `core progress 100%` at three Core sizes gives mid = 36.5 / 45.0 / 53.6 px for
48 / 56 / 64 dp (max error 0.03 px, the Core centred in its window to within 0.8 px), i.e.
`mid = 0.7773 x (size_dp x density / 2) - 5.388 x density`; the arc is read at its inner edge
(49.6..50.4 px at 64 dp) because the orb's own membrane reaches ~137 luma in the same band, and
angular runs shorter than 25 deg are dropped as the art's speculars (at progress 0 the gloss
fragments into nine runs, longest 24 deg, where a real arc is one long one). Result: **0 / 82 / 172 /
262 / 360 deg of painted arc against the named 0 / 25 / 50 / 75 / 100 %** — every one inside the 6 pt
tolerance this cell has always used, `VERDICT 5 shots / 0 mismatches`, and the state sweep
`17 shots / 0 mismatches` (rose hue 8–9 in FAILED alone, teal 184–195 everywhere else).

Two tool faults surfaced with it, both fixed: `profile --fixed` raised `TypeError` on the baked art
(`r_edge` is `None` when the disc fit is skipped — and `profile` is the command the tool calls the
ground truth for every band), and `tools\core_review_sheets.ps1` invoked the audit *without*
`--fixed`, so the owner's review strips shipped carrying that false 16-mismatch log. PAUSED's
"must draw its bars" assertion now reports that the bars are baked into the art (M1-2 measures them
by mark px, 599 px against idle's 1153) instead of flagging a check that can no longer apply.

**Milestone 4 — the C5 motion contract holds on the phone: freeze, resume and completion are green,
and the gate that measures them was itself mis-timed.** C5 is a statement about TIME, so it needs a
movie: `tools\core_motion.ps1` screen-records a scripted pass, ffmpeg extracts every frame at 30 fps,
and `tools\core_state_audit.py frames` measures each frame's painted arc, centre bloom and motion
against the rig's own `segments.csv`. On `test_out\core_motion` (869 frames, `CORE_ATTACH=2`,
`SERVICE_UNBIND=0`) it now reports **`VERDICT 0 of the C5 motion claims failed`**:

- **control** — the looping READY look moves (move_max 32.9 ≥ 5.0), so a frozen PAUSED reading is the
  ring stopping, not a still screen (or a dead rig);
- **freeze** — PAUSED holds **one 5.5 s frozen run** (both the disc-wide mean and the new ring-annulus
  mean ≤ 1.0) while its arc stays at **61 %**, the same 61 % it read before the pause; and the two
  stills taken 2.6 s apart inside the hold are **pixel-identical** (`diff`: mean|delta| 0.00,
  0 pixels changed > 20, tol 6) — the second, independent instrument;
- **resume** — RESUMING's arc measures **220° = the 220° it had before the pause** (61 %), not 0;
- **complete** — the ring closes at 22 633 ms and the centre's merge blooms at 25 367 ms: **ring
  first**, then COMPLETE holds 359°.

The first attempt at this gate failed 2 of the 4 claims, and the app was not at fault — the rig was.
The device's own log pinned it to the millisecond: `CORE_CMD cmd=progress 62` is stamped 17:19:42.760
and the ring lights on the very next frame (frame t 3605 ms, with the video's t=0 = device −29.5 s —
consistent to a frame with three other `CORE_STATE` anchors), while that step's window had closed
**138 ms earlier**, so its "settled" slice held pre-command frames and the comparison read 220° vs
24°. Fixed at the measuring end, not by loosening the claim: the rig holds the two ring-bearing steps
4.4 s (the poller serves a push 1.0–1.6 s later) so the change lands inside its own step, and the
analyzer now watches the **ring annulus** as well as the whole disc — a 2.6 dp stroke is ~2 % of the
disc's area, so a progress-only change is nearly invisible to the disc mean. The same stricter pair of
metrics decides the frozen runs, so a hold that keeps the orb still while the ring's sheen keeps
turning can no longer pass as a freeze. New bench channel to ask the question directly: `core.cmd
stage` logs `CORE_STAGE` on demand (`CoreHost.stageNote()`), because `CORE_STATE` only carries the
stage at the instant of a change.

Two app-side faults the first pass did expose are fixed with it, and both were invisible from the
source: the auto-settle (COMPLETING → COMPLETE, RESUMING → PROGRESS) now loads the promoted state's
file (`updateStage(true)`) — without it `core_complete`'s ring-closing and merge never played on the
real finish path, because the promotion left the stage cleared — and the stage's frames now reach the
screen at all (`CoreHost.verifyDrawable` plus an animator update listener), because a hand-drawn
`LottieDrawable` is not the view's background, so `View.invalidateDrawable` silently dropped every
repaint it asked for. That second one is what "core_pause measured its first frame for 5.7 s" was.

**Milestone 5 — sheet C3's touch physics became numbers with a gate, and the phone proved the
interaction.** The C3 figures used to live at their call sites — a View's clamp (`4f * dp`), a touch
listener's literals (`0.06f`, `12 * dp`) and an animator's inline envelope (`sin(PI * t) * 0.10f`) —
where no test could see them and a free-model edit could change how the Core *feels* without touching
a design table. They are constants on `CoreMotion` now (`MARK_LAG_DP`, `MARK_LAG_FRACTION`,
`EDGE_MAGNET_DP`, `SNAP_SQUASH`, `SNAP_BULGE_FRACTION`, `PRESS_SQUASH`, `RELEASE_SWELL`), the snap's
envelope is a pure function (`CoreMotion.snapSquash(t)`, peak exactly 0.10, zero at both ends), and
`CoreMotionTest.c3TouchPhysicsMatchesTheSheet` pins every one of them.

On the phone (`tools\core_touch.ps1` + `core_state_audit.py touch`, `DEVICE_TEST.md` §0d, 390 frames
from a recorded pass of injected gestures): a **held press** at the Core's centre is classified as a
TAP — `CORE_TOUCH down` 17:50:38.197 → `up dragging=false` 17:50:38.429, 232 ms of contact with no
drag — while the 500 ms swipes log `up dragging=true`, so the platform's own touch slop is doing what
the plan's ~8 dp threshold asked. The drag **follows the finger**: the Core's centre travelled
539 → 305 while the finger went 540 → 300. And the edge magnet fires exactly, with a control: released
**20 px from the left edge** the Core landed at **`CORE_MOVED x=0`** (magnetised), released **218 px
from it** it stayed at **`x=218`** — C3's "only within 12 dp, and never forced".

Two rig findings worth more than they look. `adb shell input tap` injects down and up **4 ms** apart,
which cuts a 400 ms press composition off after 4 ms — the first pass therefore read "the press does
not compress" and the fix was the rig's (a held same-point swipe), not the Core's: with the press
actually held, the art's edge measures **56.0 → 50.0 px = 10.7 % of rest, swelling back to 100 %**,
which is C3's "~10 % compression + overshoot". The instrument for that is the angle-averaged radial
profile's steepest falloff, not the thin ring: the whole art scales, while the ring's crossings are
narrow, partly unlit and sit beside a fixed-radius host track. And the resting Core's membrane is
genuinely dark at its bottom edge while its chevron is cyan and *brighter*, so ring crossings are
found by cyan-ness and paired at the expected radius, and the tool prints "NOT DECIDED HERE" for the
claims it cannot settle (position — the service's `CORE_MOVED` line is exact — and the snap's
flatten/bulge, whose vertical extent no scan can measure on this look).

Outstanding (M3, the rest of M5, M6–M7 of V3.3): the state-machine rename, the press/snap
deformation measurements, failure/retry/unsupported and the final device
pass — plus the owner's §6 failure/unsupported hexes and the strips review now waiting in
`test_out\core_visual_m2b\` (states, progress, and the live 48/56/64 dp comparison that settled the
**64 dp default** — owner ruling 2026-09-27).

## V3.2.0 — The Fetcher (Downi Core), shipped 2026-09-26

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

## Released

| Version | Code | Highlights |
|---|---|---|
| **3.2.0** | 48 | **The Fetcher — complete (Phase A–G).** The DOWNI Core: a glossy obsidian gel object with the sheet-2 folded-ribbon chevron (never the app logo) living over Instagram and TikTok. Detects the video you're watching and wakes (never downloads without a tap); one tap resolves via the platform's own Copy link and the perimeter becomes the real download's progress (comet head, energy flow); true PAUSE/RESUME with byte-continuation that survives process death (the vivo killer can no longer destroy a download — interrupted grabs are swept into resumable paused grabs at app open); duplicate prevention before job creation (adopt running, refuse just-saved); honest failure states; haptic voice; gel physics (drag slosh, edge squash); position memory with rotation re-clamp; screen-off zero-cost discipline; self-recovery after vivo wipes the accessibility binding (open DOWNI, or the one-line adb grant makes it automatic); settings card with armed status, daily stats, size, position reset and the vivo keep-it-alive walkthrough; debug instruments gated to debug builds (release has no spike code, no on-device logs). 56 tests / 0 failures. |
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
