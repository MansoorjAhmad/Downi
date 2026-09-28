# DOWNI v3.0 Roadmap

Shipped in v3.0.0:
- Design system: motion tokens, staggered entrances, press feedback, haptic map, reduced-motion
- Grab/Inspector/Queue: confetti, thumbnail clipboard banner, 5-min inspect cache, last-grab chip, BEST PICK + per-platform quality memory, progress ring + speed sparkline, cancel-with-Undo
- Vault 2.0: search, sort, storage stats, grid/list, multi-select bulk share/delete
- Player 2.0: double-tap seek, speed, position memory, swipe-down, tap-hide controls
- Settings: accent colors, AMOLED, clear cache, tap-to-copy version
- System: rich notifications with thumbnails, Quick Settings tile, shared-link Inspector
- Engine: TRUE 1080p via native MediaMuxer merge, concurrent fragment downloads (3, test-gated), engine warm-up
- Playlist batch: flat-extract up to 25 entries, Grab-all banner, sequential queue

Shipped in v3.1.0 ("The Polish Release"):
- DowniDrop 2.0: invisible DropActivity (v2.6.4 pattern restored), instant background grabs, self-starting engine (no app warm-up assumed), per-platform quality memory headless, Cancel action, rich saved/failed notifications, Settings toggle (Instant / Ask quality)
- Vault keyed-DOM reconciliation: cards are moved/added/removed, never rebuilt — blink fixed at the root; in-place selection; no-op refresh detection; 200ms search debounce; single-card new-arrival animation

Shipped in v3.3.1 ("The Core" — Fetcher 2.0, versionCode 49):
- The Core: overlay state machine (14 wire states), the owner's own art, wake/detect/resolve/job binding, press/drag/snap with position memory, the C5 motion contract (freeze / resume / completion), C6 failure and recovery, the bench channel
- Cost: the two `iterations: Infinite` hero animations now rest and play one pass on a tap (~0 time at rest), the progress sweep and the skeleton shimmer rest too (565 → 0 frames, 303 → 0 frames per 9 s window) — the vortex, sweep and shimmer fixes together end the "animations nobody asked to see" bill
- UI: the touch-target sweep on all four screens (Vault 30 → 2 controls under 48 px, Settings 13 → 5), the inspector's primary action is a sticky row instead of a control below the fold
- Core states: an explicit `Kind` (REST/TRANSIENT/HOLD/TOUCH) plus a settle/legality table — the state machine's rules are data now, and 18 suites / 123 tests hold them

Deferred / next candidates:
- The vivo ABE vendor-kill recount (§0h): how often the process is taken, over a longer window than any pass has had yet
- Vault Phase 2 polish (content-visibility, press-scale) — the shimmer skeletons shipped and are bounded now
- Pause + resume (needs .part support - a deep change to the proven core; requires the full matrix)
- TikTok photo-post saving (needs a multi-file save contract)
- Web: direct-to-CDN downloads where CORS allows (skip the proxy)
- Play Store track: AAB, targetSdk 35+, ProGuard rules

Law of the land (READ_THIS_BEFORE_UPGRADE.md):
- No client spoofing, no forced UAs, no Instagram sessions, no segmented-socket hacks.
- Every extraction-layer change passes the full 3-platform x 3-entry-point matrix on a real device before it ships.