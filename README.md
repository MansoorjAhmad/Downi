# DOWNI V3.0.2 ⚡

> **Grab any video. One tap. Zero clutter.**

DOWNI (formerly OmniDownloader) is a sleek, ultra-luxury Android application engineered to download high-definition, platform watermark-free videos and crystal-clear audio from all major social media platforms.

---

## 🆕 What's New in V3.0.2

### Fixes + gate-keeping
- **Text-selection share works** — "DOWNI" in Android's text menu opens the Inspector (PROCESS_TEXT was wired in the manifest but never handled).
- **No more double Inspector** — cold-start shares are consumed once.
- **Vault always lists your downloads** — the app now asks for media access when you open the Vault (Android 13+ requires it in code, not just the manifest).
- **Honest 1080p on every video** — VP9/AV1 ladders merge on Android 10+/14+; older phones get a clear "try 720p" message instead of a broken file.
- **Device test matrix is now a repo file** (`DEVICE_TEST.md`) — The Law's gate is executable, checkboxes included.

---

## 🆕 What's New in V3.0.1

### The hardening release — everything fixed, everything honest
- **Playlist batch never dies** — a failed video is skipped, the rest keep grabbing
- **DowniDrop, for real** — shared links now open the Inspector (quality picker) instead of a silent background grab that never showed in the Queue
- **Cancel keeps its Undo** — the Undo action no longer gets overwritten by a second generic toast
- **Right link, right job** — history, "Last grabbed ↻" and live progress cards always belong to their own download, even with 3 parallel grabs
- **Truthful settings** — clipboard toggle restores its real state; session stats survive restarts; the duplicate "Storage" card is gone (now Behavior + Storage)
- **Better notifications** — unique per job, completions actually make a sound, permission asked on your first Grab instead of at launch
- **True 1080p, smoother** — the MediaMuxer merge now writes properly interleaved files with a larger safety buffer
- **Identity cleanup** — the whole app is DOWNI now (plugin, service, resources, settings keys — with automatic migration, nothing is lost); accent colors repaint the *entire* interface; three honest platform chips; one SVG icon language; one tagline; AMOLED black reaches the system bars
- **Housekeeping** — stale update APKs auto-cleaned, Instagram diagnostic can't fail on a dead probe, no more stale-artifact files in the repo


## 🆕 What's New in V3.0.0

### The smooth-and-fast release
**Feel**
- Design system with a single motion language — springy sheets, staggered lists, press feedback, success pulses, reduced-motion support
- Vortex confetti on every save, haptics mapped to every action
- Vault 2.0: search, sort, storage stats, grid ⇄ list, **long-press multi-select with bulk share/delete**
- Player 2.0: double-tap ±10s, speed control, **resumes where you left off**, swipe-down to close, tap to hide controls
- Queue: progress ring, live speed sparkline, cancel with **Undo**
- Grab: clipboard banner with **video thumbnail**, "Last grabbed ↻" chip, invalid-link shake
- Inspector: **BEST PICK** badge, remembers your quality per platform, blur-up thumbnails
- Settings: 4 accent colors, **AMOLED true-black**, clear engine cache, tap-to-copy version
- Rich notifications with video thumbnails, Quick Settings tile, shared links open the Inspector

**Speed**
- **True 1080p Full HD on YouTube** — separate video + audio downloaded and merged on-device with Android's native MediaMuxer (no ffmpeg bloat)
- **Instant re-inspect** — same link re-opens the picker in milliseconds (5-minute cache)
- **Concurrent fragment downloads** (3) for faster HLS/DASH fetches
- Engine pre-warmed at launch — the first grab starts faster
- Downloads continue in the background — the foreground service keeps the engine alive

**Power**
- **Playlist batch mode** — paste a YouTube playlist, tap Grab all, watch them queue one by one
- Vault custom-folder files are fully integrated (Open / Share / Delete)

---

## 🆕 What's New in V2.6.7

### The Vault, fixed for real this time
- **Custom-folder downloads now appear in the Vault** — files saved to your chosen folder are listed straight from that folder (Open / Share / Delete), deduped against the gallery.
- **Media scan after every save** — custom-folder saves are handed to the media scanner, so they also show up in Gallery apps and the Vault's gallery view.
- **Rescan button** — Vault → Rescan re-indexes Movies, Music and Downloads if storage ever lags behind.
- **Diagnostics honesty** — the TikTok check now exercises the real API instead of a bare HEAD that always 403'd.

---

## 🆕 What's New in V2.6.6

### Perfection pass — everything honest, nothing stale
- **Build-sync guard** — Gradle refuses to build if the bundled UI is stale (`preBuild` enforces `npx cap sync android`), so an outdated interface can never ship again.
- **Honest quality picker** — YouTube shows its real maximum (720p, since no ffmpeg merger is bundled yet — true 1080p merging is planned for v2.7); other platforms offer up to 1080p. Clear notes replace silent lies.
- **Custom save folder, finished** — Settings shows the real save location, and DowniDrop background downloads honor it too.
- **Updater hardened** — points at the correct `MansoorjAhmad/Downi` repo, pre-checks "Install unknown apps", and explains unavailable qualities plainly.
- **Polish & security** — adaptive launcher icon, brand-matched splash, haptics on key actions, Vault player loopback server locked with a per-session token, cleartext HTTP restricted to the local player only, dead Capacitor plugins removed.

---

## 🆕 What's New in V2.6.5

### In-App Player + Full Light Theme
- **In-app Vault player** — play downloaded videos and audio right inside DOWNI: full-screen video, seek bar, ±10s, prev/next through the Vault, audio supported. "Open external" still one tap away.
- **Complete light theme** — header, sheets, cards, skeletons, backdrops and the vortex all adapt; first launch follows your system preference (Settings → Appearance still overrides); Android status/navigation bars follow the theme.
- **Banned feature removed for good** — the old Instagram session-harvesting login code is fully deleted from the codebase (see `READ_THIS_BEFORE_UPGRADE.md`, now tracked in the repo), and the dead Cloud Boost relay folder is gone.

---

## 🆕 What's New in V2.6.3

### Light Theme + Vault UI
- **Light / Dark theme toggle** — Settings → Appearance. Switches instantly, persists across restarts with zero flash on launch.
- **Vault tab fully functional** — Browse, play, share, and delete all files downloaded by DOWNI. Filter by All / Video / Audio.

---

## 🆕 What's New in V2.6.2

### Restored Core Engine
- Restored the proven v1.3.6 downloader core to fix Instagram and YouTube downloads.
- Removed experimental client-spoofing and forced user-agent overrides that broke extraction.
- Clean, stable extraction baseline for all supported platforms.

---

## 🌟 Core Downloading Features

1. **⚡ One-Tap Grab**
   - Copy any video link — a banner appears instantly. Tap it to grab at best quality.

2. **🔍 Inspect & Choose Quality**
   - Paste a link, preview thumbnail + title, then pick from real available qualities (or Audio Only MP3) before downloading.

3. **↗ System Share Receiver**
   - In any app, tap **Share → DOWNI** — downloads in the background with progress notifications and 1-tap Play/Share when done.

---

## 🚫 100% Watermark-Free

| Platform | Method |
|----------|--------|
| **YouTube & Shorts** | HD via yt-dlp (up to 720p muxed; 1080p merging planned) |
| **Instagram** | Clean MP4 — Reels, Stories, Posts |
| **TikTok** | Direct HD CDN (`hdplay`) — no bouncing watermark |
| **Facebook** | Clean CDN stream |
| **X (Twitter)** | Native video extraction |
| **Reddit / Pinterest** | Clean CDN streams |

---

## 💎 Features

| Feature | Details |
|---------|---------|
| **Platforms** | YouTube, Instagram, TikTok, Twitter/X, Facebook + any direct link |
| **Formats** | Up to 1080p (YouTube: up to 720p), 480p, Best, MP3/M4A audio-only |
| **Downloads** | 3 simultaneous, up to 6 in the queue — fully parallel |
| **Vault** | Browse, play, share & delete your downloaded files |
| **Theme** | Light & Dark mode — Settings → Appearance |
| **Clipboard detect** | Auto-detects copied video links — tap to grab instantly |
| **Inspector** | Preview title, thumbnail & quality before downloading |
| **Save location** | Gallery (Movies/Music) or custom folder |
| **In-app updater** | Auto-checks GitHub and installs new versions |

---

## 🏗 Architecture

- **Dual-Engine Core**: Capacitor 6 + native Java bridges + Chaquopy on-device Python (yt-dlp `2026.8.19`)
- **Parallel Queue**: `MAX_ACTIVE = 3` thread pool, `MAX_QUEUED = 6` overflow queue
- **Offline-First UI**: All web assets (Tailwind, fonts) bundled inside the APK — works with zero internet
- **Resilient Fallback**: Platform-aware multi-tier stream selection, 100% on-device

---

## 📦 Releasing a New Version

1. Run `npx cap sync android` (enforced automatically by the `preBuild` guard since v2.6.6).
2. Bump `versionCode` / `versionName` in `android/app/build.gradle`.
3. Commit and tag: `git tag vX.Y.Z && git push origin main --tags`.
4. GitHub Actions builds the signed APK and attaches it to the release automatically.
5. Users tap **Check Updates** in the app — progress bar, one-tap install.

### Signing (CI)

Repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Local builds read the same values from `android/keystore.properties` (gitignored — never commit it).

### Icons

`python tools/generate_icons.py <path-to-logo.png>` generates every launcher density, splash, and web asset from one square PNG.
