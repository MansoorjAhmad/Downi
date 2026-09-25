# DOWNI Changelog

Full release notes + signed APKs live on
[GitHub Releases](https://github.com/MansoorjAhmad/Downi/releases).

## Unreleased — V3.2 (in progress)

**The Fetcher (Downi Core), Phase A.** The Core's face exists and runs on the phone: a
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

## Released

| Version | Code | Highlights |
|---|---|---|
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
