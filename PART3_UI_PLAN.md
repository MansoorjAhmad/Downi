# Downi v3.3.2 — Part 3 (the UI polish), measured before it is touched

**Written 2026-09-28, the same day v3.3.1 shipped.** Part 3 was deferred by the plan's own rule
(`V3.3_PLAN.md` §7: "collect whatever turns up, then bundle it") and by the owner's list — flat icons,
spacing, typography. This file does what every other part of this project does first: **measure**, so the
choices are numbers and not taste. Nothing here is implemented yet; §4 is the owner's pick.

---

## 1. The instrument: `tools\core_web_audit.py`

The app is a WebView, so its layout can be asked directly — over the same DevTools socket
`core_web_anim.py` uses. The new tool walks the DOM and reports, per screen:

- **tap targets** — every interactive element's box in CSS px, and which are under **48 px**, the
  platform's own minimum for a thumb. (On this device CSS px *is* dp: `devicePixelRatio` = 2.75 =
  the screen density.)
- **below the fold** — controls whose top starts past `window.innerHeight`.
- **type scale** — every distinct `font-size / font-weight` in use, with counts and an example selector.
- **spacing** — every distinct padding value, with counts.

```
python tools\python311\python.exe tools\core_web_audit.py --tab grab|queue|vault|settings
python tools\...\core_web_audit.py --tab vault --json test_out\_web_audit_vault.json
```

Two instrument facts worth keeping, both measured today:

1. **Injected taps stopped landing on the dock** (`adb shell input tap` at the dock's own coordinates;
   the first two worked, the later ones did not). The tool now navigates the way a finger does to the
   same button — `#navQueue.click()` inside the page — so `--tab` is the reliable route.
2. **It is a single-page document**: every screen's markup is in the DOM at once, so the counts are per
   *visible* screen (the hidden ones are `display:none` and filtered out). The `--json` output carries the
   active tab and per-screen markers, so a reader can always tell which screen a row belongs to.

## 2. The measured baseline (vivo V2058, build 49 / v3.3.1's tree, 2026-09-28)

| Screen | document (css) | controls | under 48 px | below the fold | type combos | paddings |
|---|---|---|---|---|---|---|
| **Grab** | 392 x 890 | 19 | **10** | 0 | 22 | 35 |
| **Queue** | 392 x 1003 | 10 | 4 | 0 | 24 | 37 |
| **Vault** | 392 x 1526 | 50 | **36** | **16** | 24 | 37 |
| **Settings** | 392 x 1924 | 31 | **25** | **12** | 24 | 37 |

Viewport 392 x 823 css; the WebView does not fill the screen (875.6 css would), so ~53 css px are the
system bars — worth remembering for any coordinate work.

**The named offenders on Grab** (the screen a user meets first):

| control | size | where |
|---|---|---|
| `button.btn-hero` "⚡ Grab" — the app's primary action | **70.6 x 32.5** | 289.4, 103.2 |
| `button.btn` "Inspect" | 66.1 x 34.0 | 215.4, 102.5 |
| `button.btn` "Paste" | 55.5 x 34.0 | 290.5, 439.4 |
| `button#lastGrabChip` "Last: …" | 319.3 x 36.5 | 36.7, 621.3 |
| `span.chip` YouTube / TikTok / Instagram | 23.0 tall | 578.4 |
| `span#headerVersionBadge.chip` | 39.1 x 19.0 | 147.1, 14.0 |
| `button.btn.w-10` | 40.0 x 40.0 | 332.7, 12.0 |
| `button.text-zinc-500` (a header control scrolled out) | 30.0 x 22.0 | 330.0, **-80.3** |

**The type scale in use** (24 combinations; the top of the list is the story): `16px/400` x156,
`10px/400` x34, `11px/700` x28, `10px/700` x28, `12px/700` x20, `11px/400` x18, `10px/800` x13,
**`9px/800` x13**, `11px/800` x12, `12px/800` x11, `9px/400` x10, `9px/700` x9, `13px/800` x7,
`10px/600` x7 — i.e. seven sizes (9/10/11/12/13/16/17) across four weights (400/600/700/800), with a
**9 px tier** carrying 32 elements.

**The spacing in use**: 37 distinct paddings; the five most common are `8/12`, `16`, `14`, `4/8`, `12`
— i.e. a real rhythm exists, but 14 px and 12 px and 10 px and 6 px all coexist.

## 3. The candidate list (each one measured, each one optional)

| # | Candidate | The measurement | The likely fix | Risk |
|---|---|---|---|---|
| **C1** | The primary action is short | `⚡ Grab` **32.5 px** tall (the platform minimum is 48) | padding only — no redesign, no new art | low |
| **C2** | The other main controls follow it | Inspect 34.0, Paste 34.0, `lastGrabChip` 36.5, `btn.w-10` 40.0 | one shared control-height token, applied in the same pass as C1 | low |
| **C3** | The Vault's own buttons | **36 of 50** under 48 px (a row of `35.5 x 28.5` per-item buttons), **16** below the fold | the row's actions to >= 44-48 px, and a layout that keeps the first row visible | medium — the Vault's grid is dense by design |
| **C4** | Settings' rows and toggles | **25 of 31** under 48 px, **12** below the fold | same token as C2/C3 | medium |
| **C5** | The type scale | 24 combinations, 7 sizes, 4 weights, a **9 px tier on 32 elements** | collapse to ~5 sizes (e.g. 10 / 12 / 14 / 16 / 20) and 2-3 weights; kill 9 px | medium — touches every screen's look |
| **C6** | The spacing rhythm | 37 distinct paddings | a 4-step scale (4 / 8 / 12 / 16) and one 14 -> 12 sweep | medium |
| **C7** | Scrolled-out header controls | a `30 x 22` button at y = **-80.3**, a 65.5-tall header at y = -100 | keep as a scroll-away header, or pin the controls: an owner call | low if pinned, medium if redesigned |
| **C8** | The inspector's confirm control sits below the fold | measured by hand in `DEVICE_TEST.md` §0k: device **y=2416 on a 2408-px screen** | bring the primary action into view without scrolling | medium — the sheet's layout |
| **C9** | The flat icons | **not measurable by this tool** — flatness is a judgement | the owner names the icons; then they are one asset pass | low |

## 4. The decisions (taken 2026-09-28 — the handle was delegated, so they are made here)

The rule applied to every row: **polish, not redesign** (the standing ruling), and *anything that changes
the look beyond its own defect waits for the owner*. That splits the nine into what is implemented, what
is declined with a reason, and what genuinely needs the owner's taste.

**TAKEN — implement in this order, each proven by re-running the audit (the §2 table is the control):**

| # | The change | Why it is safe |
|---|---|---|
| C1 | `btn-hero` (the primary action) gets `min-height: 48px` — padding only | no layout, no art, no copy: the pill grows to the platform's own minimum |
| C2 | the shared `btn` control height becomes >= 44 px (Inspect, Paste, `lastGrabChip`, `btn.w-10`) | one token, applied once; the same token then covers C3/C4's *heights* without touching their layouts |
| C5a | the **9 px tier is raised to 10 px** (32 elements measured: `9px/800` x13, `9px/400` x10, `9px/700` x9) | pure legibility; the size scale itself is not otherwise touched |
| — | the platform **chips stay labels, not controls** | recorded so nobody "fixes" their 23 px later: they are status text (YouTube / TikTok / Instagram), not buttons |

**DECLINED, with the reason written down:**

| # | Why not |
|---|---|
| C5b | collapsing 7 sizes / 4 weights into ~5 and 2-3 changes every screen's texture — that is a redesign, and the ruling forbids redesign pivots in a polish release |
| C6 | the 14 px paddings are 12 instances inside an otherwise real rhythm (8/12, 16 dominate); sweeping them buys ~nothing and risks silent layout shifts on screens the audit cannot see scrolled |
| C7 | **the audit's own finding was wrong, and this corrects it**: the `30 x 22` box at y=-80.3 and the 65.5-tall header at y=-100 belong to the **hidden screens' markup** in this single-page DOM (all screens exist at once — §1). They are not scrolled-out controls; there is nothing to fix |

**DEFERRED to the owner (not a technical blocker — a taste or product call):**

| # | The question |
|---|---|
| C8 | the inspector's confirm control below the fold (`DEVICE_TEST.md` §0k: device y=2416 on a 2408-px screen): may the sheet's *shape* change, or should the control simply come up into view? |
| C9 | the flat icons: name them, and their style stays the existing one |

**Order of work:** C1 + C2 + C5a in one pass (all three live in `www/index.html`'s style block), then
`cap sync`, the audit re-run on all four screens, a release-build device pass, and then v3.3.2's own
release through `V3.3.1_PLAN.md` §2's proven V0-V5 checklist.

**Result, measured 2026-09-28 after the pass** (the same tool, the same screen):

| | before | after |
|---|---|---|
| Grab - controls under 48 px | **10 of 19** | **6 of 17** |
| Grab - primary action height | 32.5 px | **47.5 px** (CSS 48 — half a px of layout rounding) |
| Grab - Inspect / Paste / last-grab chip / `btn.w-10` | 34.0 / 34.0 / 36.5 / 40.0 | **48 / 48 / 48 / 48** |
| Grab - document height | 890 | 916 (the taller pills) |
| Type scale, one screen | **24 combinations** | **19** — the 9 px tier is gone (20 occurrences raised to 10 px) |

The six that remain are deliberate, not missed: the three platform chips and the version badge are
**labels** (recorded above), the off-screen box belongs to the hidden screens' markup (C7), and the hero
rounds to 47.5 px.

C2 was revised on evidence: its first value was 44 px, and the next audit run showed four controls still
under the platform line — this is Android, so the token is 48.

## 5. The gate, when it is implemented

- **Before / after, in numbers**: `core_web_audit.py` on all four screens + the inspector — the same
  table in §2, re-measured. A candidate that does not move its number did not land.
- **The app's honesty re-checks** (the Law §6 / `DEVICE_TEST.md`): any change touching live state,
  counters, notifications or the ledger re-runs those cells on a real phone.
- **A device pass** on the release build for the screens that changed, then the usual release mechanics
  (`V3.3.1_PLAN.md` §2, V0-V5) — v3.3.2, one focused release.

## 6. Not in v3.3.2

- **The Core is frozen**: nothing in `downicore/`, no new art, no timing changes. It shipped today and
  the sheets are green (`DEVICE_TEST.md` §0l-§0p).
- **No new features and no engine touches** — those carry their own matrix (the Law §4).
- **The Settings size control and the failure/unsupported hexes** stay the owner's calls, as §6 of the
  3.3.1 plan records.

