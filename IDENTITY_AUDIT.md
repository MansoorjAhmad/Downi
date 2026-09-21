# DOWNI — IDENTITY AUDIT (v3.0.0 codebase, 2026-09-21)

Mandate: **no new features — strengthen the current identity.** This audit covers every
user-facing surface of the Android app (www/index.html, 2,606 lines) plus native brand
surfaces (manifest, strings, styles, splash, README). Web parity audit is Phase 2.

Verdict up front: the *foundation* is genuinely strong (coherent dark palette, two good
fonts, one motion language, a real signature element in the Vortex). What's broken is
**discipline**: five names for the same features, a ghost identity in the code, an accent
system that only half-applies, three icon languages, and one honesty bug.

---

## A. THE GHOST IDENTITY — "Omni" is still everywhere

User-facing surfaces say DOWNI (header, `app_name`, README). Internally the app is still Omni:

| Where | Ghost |
|---|---|
| `applicationId` / package | `com.omnidownloader.app` |
| Plugin + service classes | `OmniEnginePlugin`, `OmniDownloadService`, JS `Plugins.OmniEngine` |
| localStorage keys | `omni_theme`, `omni_accent`, `omni_amoled`, `omni_q_*`, `omni_pos_*`, `omni_clipboard_detect` |
| Resources | `omni_splash.xml`, `omni_logo_foreground`, `omni_app_icon` |
| Repo root | `recovered-apk-ui.html` (stale artifact) |
| README | "(formerly OmniDownloader)" |

**⚠️ CONSTRAINT — read twice:** `applicationId` **must stay `com.omnidownloader.app` forever.**
Changing it breaks in-place updates for every installed user (Android treats it as a new app).
Same caution for localStorage keys — renaming them orphans saved user settings unless migrated
with fallback reads. The ghost is invisible to users; exorcise it only where it's free:

- ✅ Safe: Java class/file renames, drawable/resource renames, deleting `recovered-apk-ui.html`
- ⚠️ Migrate-with-fallback: localStorage keys (read `omni_*`, write `downi_*`)
- ❌ Never: `applicationId`

## B. FIVE NAMES FOR THREE FEATURES

The handoff canon says **Vortex / Inspect / DowniDrop**. The UI says something else on every screen:

- The Vortex button: labeled *"Tap the vortex"*, but its toast says *"**Fast DL** needs a link"*,
  its `alt` text says *"Fast DL"*, its handler is `handleFastDlClick`
- The Inspector flow: button says *"GRAB THIS LINK"*, sheet is the Inspector, code says `inspect`
- Share-into-app: hint card says *"THE DOWNI GRAB"*, docs say *DowniDrop*, UI never says either
- Verbs in the wild: Grab, Fast DL, re-grab, Download, Scan ("Scanning qualities…" vs "SCANNING…")

**Fix — canonize a vocabulary and enforce it everywhere:**

| Canonical term | Meaning | Rules |
|---|---|---|
| **Grab** | the verb. Everything download is "grab" | Buttons, toasts, docs |
| **Vortex** | the hero button only | Always "the Vortex", never "Fast DL" |
| **Inspector** | the quality sheet | Title case |
| **DowniDrop** | share-sheet entry into DOWNI | Use in hint card + docs |
| ~~Fast DL~~ | banned word | grep and destroy |

## C. THE ACCENT SYSTEM ONLY HALF WORKS *(biggest visual bug)*

Settings promises 4 accent colors. `setAccent()` rewrites `--accent` / `--accent-2`… but the UI
is riddled with **hardcoded cyan** that never changes: `rgba(34,211,238,…)` in `.card-accent`,
`.btn-hero` shadow, `.chip.on`, `.vortex`, error-sheet quick-fixes box, and dozens of
`text-cyan-300` / `bg-cyan-400/12` / `border-cyan-300/20` Tailwind utilities across every screen.
38 distinct hex values total; most should be token references.

**Result:** user picks Rose accent → half the app stays cyan. Identity promise broken.

**Fix:** introduce `--accent-rgb` / `--accent-2-rgb` triplets, rewrite every
`rgba(34,211,238,α)` as `rgba(var(--accent-rgb),α)`, add `.text-accent / .bg-accent-soft /
.border-accent` utility classes, sweep all `*-cyan-*` utilities. One evening of work, huge payoff.

## D. THREE ICON LANGUAGES

1. Lucide-style stroke SVGs (nav bar, link input, quality cards) — *the good one*
2. Emojis as functional icons (🚀 updates, ⚙️ engine, 🔍 diagnostics, 📋 copy/clipboard, ⭐ GitHub, ↗ hint)
3. Text glyphs as buttons (✕ ↻ ▶ ⏸ ← → +10s)

**Fix:** stroke SVGs for anything functional (one inline SVG sprite, `stroke-width:2`, 24px grid).
Emoji allowed only decoratively (confetti, celebration). ✕/↻ become SVGs.

## E. HONESTY BUG — platform chips overpromise

Grab tab chips: **YouTube · TikTok·HD · Instagram · Facebook · X · Reddit.**
The Law: only Instagram / YouTube / TikTok are matrix-proven. Facebook/X/Reddit chips are the
same class of lie as the web's fake YouTube badge we removed in v1.1. Bonus oddity: the TikTok
chip has a hardcoded `.on` class for no functional reason.

**Fix:** show the 3 proven platforms as chips; drop the rest until they pass the matrix.

## F. SETTINGS STRUCTURE BUG

Two adjacent cards both titled **"Storage"** (lines ~582 and ~601 — the second has a copy-pasted
`<!-- Storage -->` comment and only holds "Clear engine cache"). Clipboard auto-detect lives in
"Storage" but is behavior, not storage.

**Fix:** `Storage` = save location + clear engine cache. New `Behavior` card = clipboard
auto-detect. Fixes information scent and kills the duplicate heading.

## G. COPY VOICE — good bones, no rules

Toasts are already excellent (short, warm, consistent: "Grab queued — DOWNI is on it").
Inconsistencies are in casing and the tagline:

- Buttons: `GRAB THIS LINK` (shout-caps) vs `Download & install` vs `Try again` vs `BEST PICK`
- Tagline ×3: title *"Grab Any Video"* · header *"Grab any video. One tap."* · README *"Grab any
  video. One tap. Zero clutter."*

**Fix — style rules:** sentence case for buttons; CAPS reserved for badges/eyebrow labels
(BEST PICK, LINK DETECTED). Canonical tagline everywhere: **"Grab any video. One tap."**

## H. WHAT'S ALREADY STRONG (do not touch)

- Palette: `#060A13` deep-space bg + cyan→blue accent gradient reads as one product
- Type: Plus Jakarta Sans + JetBrains Mono is a distinctive, deliberate pairing
- Radius language: 24px cards / 16–18px controls / pill chips — codify, don't redesign
- The Vortex as hero: real signature element. Deserves the name being used *consistently*
- Splash `#060A13` matches app bg — launch feels native (note: splash is dark even in light
  theme; known cosmetic limitation of a static splash color)
- Motion tokens, stagger, press feedback, reduced-motion — the "feel" work from v3.0 landed

---

## REMEDIATION PLAN → ships as **v4.0.0** (no dot-release grind)

All UI/copy/resource-layer. **No extraction-layer changes → The Law's full matrix is not
re-triggered by this work** (the still-pending v3.0.0 device matrix remains its own gate).

| Phase | Work | Touches |
|---|---|---|
| I-1 | **Canon vocabulary + copy sweep** (Fast DL banned, tagline unified, casing rules, Settings restructure) | index.html copy |
| I-2 | **Accent token plumbing** (`--accent-rgb`, utility classes, cyan sweep) | index.html CSS/classes |
| I-3 | **Icon unification** (SVG sprite, emoji/glyph replacement) | index.html markup |
| I-4 | **Honesty chips** (3 proven platforms) + delete `recovered-apk-ui.html` | index.html, repo |
| I-5 | **Ghost exorcism (safe parts)**: Java class + drawable renames, localStorage migration w/ fallback | Java, res, JS |
| I-6 | **Web parity audit** of downi-web against the same rules | downi-web repo |

Then: full visual QA pass on device → v4.0.0 (versionCode 41).

---

## FIX LOG — B1–B20 (2026-09-21, executed + validated)

Build: `assembleDebug` **BUILD SUCCESSFUL** · JS: both inline script blocks pass `node --check` · Python: `py_compile` OK.
No extraction-layer changes were made (TikTok/YouTube/Instagram engine logic untouched — The Law intact).

| # | Status | What changed |
|---|---|---|
| B1 | ✅ | Batch continues on failure: `pumpPlaylist()` runs from both success and error paths |
| B2 | ✅ | Cancel no longer double-toasts / kills Undo (generic cancel toast removed) |
| B3 | ✅ | DowniDrop wired for real: `ShareReceiverActivity` deleted, SEND/PROCESS_TEXT filter moved to MainActivity — shares now open the Inspector (as README promised) |
| B4 | ✅ | History + last-grab chip record the job's own URL |
| B5 | ✅ | Progress events carry `url`; pending cards matched by URL before FIFO fallback |
| B6 | ✅ | Clipboard checkbox restores its real saved state at boot |
| B7 | ✅ | `grabbedBytes` persisted (`bytes_total`) — stats survive restart |
| B8 | ✅ | Resolved by B3 (no more invisible background-only path) |
| B9 | ✅ | Duplicate `onShareReceived` registration removed |
| B10 | ✅ | Playlist Grab-all honors the chosen Inspector quality |
| B11 | ✅ | Unique completion-notification ids per job |
| B12 | ✅ | Two channels: `downi_progress` (LOW) + `downi_alerts` (DEFAULT, sounds) |
| B13 | ✅ | POST_NOTIFICATIONS asked on first Grab, not at launch |
| B14 | ✅ | Update APK cleaned at launch + on cancel (`downi-update.apk`, legacy name too) |
| B15 | ✅ | Mp4Merger: 8 MB buffer + true PTS-interleaved muxing |
| B16 | ✅ | Ghost exorcism: plugin `DowniEngine`, `DowniDownloadService`, `downi_*` resources/prefs/cache/localStorage with legacy fallbacks; stale comments + `recovered-apk-ui.html` deleted; UA fallback no longer a version |
| B17 | ✅ | AMOLED true-black reaches status/nav bars (`setNativeTheme` amoled param) |
| B18 | ✅ | Instagram diagnostic falls back to reachability instead of failing on a dead probe reel |
| B19 | ✅ (documented) | Verified: TikTok uses its own HD path via the tikwm API (direct no-watermark MP4). Proven core — untouched, flagged as external dependency |
| B20 | ✅ (documented) | yt-dlp pin policy comment in `build.gradle` (monthly / on-break cadence, matrix required per The Law) |
| + | ✅ | Identity canon sweep: Fast DL banned, tagline unified ("Grab any video. One tap."), sentence-case buttons, 3 honest platform chips, Behavior/Storage split, SVG icons replace functional emoji/glyphs, accent tokens (`--accent-rgb`) repaint the whole app |

**STILL PENDING (not code):** the on-device matrix — Instagram + YouTube + TikTok × Vortex/Inspect/DowniDrop, YouTube 1080p merge + playlist Grab-all. Reinstall the debug APK, then run it. After the matrix passes: tag + release.

