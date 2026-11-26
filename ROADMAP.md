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
- Cost, part one: the two `iterations: Infinite` hero animations now rest and play one pass on a tap (~0 time at rest) — the vortex fix, in a window that read 606/607 frames and ~+25 s of CPU per 20 s before it
- The published v3.3.1 artefact predates the completion pass and the Part 3 UI work — the web state in it is 174 641 B, and its sweep and shimmer still loop. Those ship in v3.3.2 below, not by moving this tag

Shipped in v3.3.2 ("the finish pass" — versionCode 50):
- UI: the touch-target sweep on all four screens (Vault 30 → 2 controls under 48 px, Settings 13 → 5 real, horizontal overflow `+0.0 px`), and the inspector's primary action is a pinned row instead of a control below the fold
- Cost, part two: the progress sweep and the skeleton shimmer rest and bound too (565 → 0 frames, 303 → 0 frames per 9 s window) — with the vortex fix, the "animations nobody asked to see" bill is closed, and the release build at rest reads 0 frames / 0 janky
- Core states: an explicit `Kind` (REST/TRANSIENT/HOLD/TOUCH) plus a settle/legality table — the state machine's rules are data now, the wire names are pinned byte-identical, and 18 suites / 123 tests hold them
- Verified end to end on the shipped artefact: a real link → a byte-identical file → a Vault row, no crash, and the installed binary hashed on the device to the local APK's own sha256

Shipped in v3.8.0 ("The Anti-Repost Shield & Engine Integrity Release" — versionCode 60):
- Pause / Resume permanently removed across notifications, Queue UI, Android bridge, background service, Python engine, and progress listener contracts. Interrupted grabs discard honestly and restart from zero.
- Anti-Repost Shield S2: software MP4/H.264/AAC remux with orientation hint retention (MediaMuxer), ISO-BMFF free box injection with random entropy, and MediaExtractor-based track/sync-sample validation (platform effect untested).
- Title & Filename Sanitizer: robust TitleSanitizer preserving international scripts (Urdu, Arabic, Chinese, Emoji) while keeping filenames within filesystem limits.
- Engine Pin & Parity: pinned `yt-dlp==2026.10.29.232701.dev0` and `certifi==2026.10.23` with PyPI wheel SHA-256 drift verification in CI.
- Engine Settings Option B: removed misleading mock health badges, fake version checks, and dead notification spam.
- Real Network Diagnostics: DNS, TLS, and reachability checks across YouTube, TikTok (official + helper), and Instagram with hard worker-thread timeouts and sanitized native clipboard export.
- Single Source of Truth for App Version: `build.gradle` is the sole source for version; web UI reads from native app info dynamically.

Deferred / next candidates:
- The vivo ABE vendor-kill recount (§0h): how often the process is taken, over a longer window than any pass has had yet
- Vault Phase 2 polish (content-visibility, press-scale) — the shimmer skeletons shipped and are bounded now
- TikTok photo-post saving (needs a multi-file save contract)
- Web: direct-to-CDN downloads where CORS allows (skip the proxy)
- Play Store track: AAB, targetSdk 35+, ProGuard rules

Shipped in v4.0.0 ("The Native Kotlin & Compose Rebuild" — versionCode 66):
- Architecture: Migrated from Capacitor/WebView/Java to **Kotlin + Jetpack Compose + Python (Chaquopy)** (2 core languages).
- Core: Room DB (`DownloadJobEntity`), DataStore (`SettingsRepository`), `WorkManager` (`QueueRecoveryWorker`), unified `DownloadCoordinator` + `DowniDownloadService`, `DropActivity`, `AppUpdater` (OkHttp 4).
- UI: Obsidian + Electric Lime Compose UI, live Grab-tab progress, instant clipboard detection, playlist auto-routing, real thumbnails in Grab & Vault, L3 Shield Report dialog, and rich DowniDrop notifications (progress %, Share, Retry).
- Engine: `yt-dlp==2026.11.24.232653.dev0`, R8 minification enabled, Capacitor/Node/WebView/spike scripts removed.

Planned for v4.0.0 ("The Bulletproof Zero-Cookie & Flagship Release"):
- **Phase 1 — Zero-Cookie Rate-Limit Defense & Extraction Resilience:**
  - Per-platform pacing (`DownloadCoordinator`: serialize Instagram 1-at-a-time with 1.5–2.5s jitter; keep 3 concurrent for YouTube/TikTok).
  - Adaptive 60s platform cooldown on `rate_limited` with honest IP/network-switch guidance.
  - Instagram public `/embed/captioned/` fallback when main `yt-dlp` endpoint hits anonymous rate-limits.
  - Surgical YouTube 403/bot-check fallback retry (only on failure, never forced globally).
  - Weekly automated `yt-dlp` nightly qualification CI (`.github/workflows/update-ytdlp.yml`).
- **Phase 2 — Flagship UX & Shield Completeness:**
  - In-app `Media3` (ExoPlayer) preview player inside Vault (scrub, speed, one-tap Repost/Share).
  - Multi-file Carousel support (Instagram & TikTok photo/video slides) into `MediaStore`.
  - Deep Creator Shield audio micro-uniqueness (optional subtle resample/dither alongside `EGL14` video transform).
  - Android Quick Settings `TileService` for instant clipboard grab / Shield toggle.
- **Phase 3 — Multi-OEM Verification & Release:**
  - Full 3-platform x 3-entry-point matrix + Shield ON/OFF upload validation + `release.yml` signed v4.0.0 APK.

Law of the land (READ_THIS_BEFORE_UPGRADE.md):
- No client spoofing, no forced UAs, no Instagram sessions, no segmented-socket hacks.
- No pause/resume, no `.part` continuation, no resumable job state. Removed in 3.8.0 and permanently banned (`DECISIONS.md`).
- Every extraction-layer change passes the full 3-platform x 3-entry-point matrix on a real device before it ships.