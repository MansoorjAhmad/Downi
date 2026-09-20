# DOWNI V2.6.3 ⚡

> **Grab any video. One tap. Zero clutter.**

DOWNI (formerly OmniDownloader) is a sleek, ultra-luxury Android application engineered to download high-definition, platform watermark-free videos and crystal-clear audio from all major social media platforms.

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
| **YouTube & Shorts** | Full source quality via yt-dlp |
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
| **Formats** | 1080p / 720p / 480p / Best / MP3 audio-only |
| **Downloads** | 3 simultaneous, up to 9 queued — fully parallel |
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
- **Resilient Fallback**: Platform-aware multi-tier stream selection with Cloud Boost relay

---

## 📦 Releasing a New Version

1. Bump `versionCode` / `versionName` in `android/app/build.gradle`.
2. Commit and tag: `git tag vX.Y.Z && git push origin main --tags`.
3. GitHub Actions builds the signed APK and attaches it to the release automatically.
4. Users tap **Check Updates** in the app — progress bar, one-tap install.

### Signing (CI)

Repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Local builds read the same values from `android/keystore.properties` (gitignored — never commit it).

### Icons

`python tools/generate_icons.py <path-to-logo.png>` generates every launcher density, splash, and web asset from one square PNG.
