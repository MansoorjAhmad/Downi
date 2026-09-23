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

Deferred / next candidates:
- Vault Phase 2 polish (shimmer skeletons, content-visibility, press-scale) — parked for v3.2; deliberately cut from v3.1.0 so the paint-timing fix ships isolated
- Pause + resume (needs .part support - a deep change to the proven core; requires the full matrix)
- TikTok photo-post saving (needs a multi-file save contract)
- Web: direct-to-CDN downloads where CORS allows (skip the proxy)
- Play Store track: AAB, targetSdk 35+, ProGuard rules

Law of the land (READ_THIS_BEFORE_UPGRADE.md):
- No client spoofing, no forced UAs, no Instagram sessions, no segmented-socket hacks.
- Every extraction-layer change passes the full 3-platform x 3-entry-point matrix on a real device before it ships.