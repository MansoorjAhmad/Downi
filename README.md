# DOWNI V2.5 ⚡

> **Grab any video. One tap. Zero clutter.**

DOWNI (formerly OmniDownloader) is a sleek, ultra-luxury Android application engineered to download high-definition, platform watermark-free videos and crystal-clear audio from all major social media platforms.

---

## 🆕 What's New in V2.5 — The DOWNI Rebrand

1. **💫 Full Rebrand: DOWNI**
   - New name, new identity, same beloved engine.
   - All notifications now come from **DowniDrop**, the background share downloader.
   - Install directly over OmniDownloader V2.0/V2.1 — same app identity, same signing key, so **Check Updates carries every user to DOWNI automatically**.

2. Everything from V2.1:
   - True in-app updater with live progress bar and 1-tap install
   - Real Media Vault with thumbnails, playback, sharing, and deletion
   - yt-dlp `2026.8.19` pinned with a YouTube client fallback cascade
   - Real available qualities in Inspect, honest error messages, real cancel
   - Offline-first UI (Tailwind + fonts bundled in the APK)

---

## 🌟 3 Core Downloading Features

1. **⚡ FAST DL (One-Tap Circle Action)**
   - Copy any video link and tap the **Crystal Vortex Button** — DOWNI grabs the best quality instantly.

2. **🔍 Manual Download (Inspect & Choose Quality)**
   - Paste a link, tap Inspect, and pick from the qualities that actually exist for that video (or Audio Only MP3).

3. **↗ THE DOWNI GRAB (System Share Receiver)**
   - In any app, tap **Share → DOWNI** — the video downloads in best quality in the background, with progress notifications and 1-tap Play/Share when done.

---

## 🚫 100% Platform Watermark-Free

- **TikTok**: Direct HD CDN extraction (`hdplay`) — no bouncing watermark.
- **Instagram**: Clean MP4 streams for Reels, Stories, and Video Posts.
- **YouTube & Shorts**: Full source quality extraction.
- **Facebook, X (Twitter), Reddit, Pinterest**: Clean CDN streams saved to your Gallery / Movies.

---

## 💎 Features & Architecture

- **Glassic Luxury Design**: Frosted glass surfaces, neon cyan accents, obsidian depth.
- **Media Vault**: Real on-device library with thumbnails, playback, sharing, deletion.
- **Dual-Engine Core**: Capacitor 6 + native Java bridges + Chaquopy on-device Python (yt-dlp).
- **Resilient Fallback Cascade**: Platform-aware multi-tier stream selection.
- **Offline-First UI**: All web assets bundled locally — perfect rendering with zero internet.

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
