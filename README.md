# OmniDownloader V2.1 ⚡

> **The Flagship Multi-Platform Video & Audio Downloader for Android**

OmniDownloader is a sleek, ultra-luxury Android application engineered to download high-definition, platform watermark-free videos and crystal-clear audio from all major social media platforms.

---

## 🆕 What's New in V2.1

1. **🚀 True In-App Auto-Updater**
   - "Check Updates" now performs a real version comparison against your installed build.
   - The update APK downloads **inside the app with a live progress bar** (MB counter + speed), then launches the installer with one tap — no browser, no manual downloads.
   - Silent background update check on launch: the update modal only appears when a new version actually exists.
   - New GitHub Actions release pipeline: pushing a `v*` tag builds the signed APK and publishes the GitHub Release automatically.

2. **💎 Real Media Vault**
   - The Vault now scans your phone's actual Movies/Music library through MediaStore.
   - Real video thumbnails and audio album art, ALL/VIDEO/AUDIO filters, size and date labels.
   - Play in any player, share anywhere, delete with the system confirmation dialog.

3. **🛠 Critical Fixes**
   - "Try Again" after a failed download no longer crashes.
   - Cancel now **actually stops** the transfer and removes partial files (previously it kept downloading in the background).
   - Honest error messages everywhere — network failures during update checks no longer claim "Up to Date".
   - Version number shown in the app is read from the real installed build.
   - Download notifications now open the app when tapped.

4. **⚡ Engine Upgrades**
   - yt-dlp pinned to `2026.8.19` (freshest extractors, built into the APK).
   - YouTube fallback cascade: on bot-detection blocks the engine retries with iOS → TV → Web Safari → Android VR → TV Embedded player clients.
   - Inspect now shows the **real available qualities** for each video (derived from the source's own format list) instead of a fixed list.
   - "Verify Engine Health" in Settings reports the actual yt-dlp + Python versions.

5. **🎨 Offline-First UI**
   - Tailwind and both font families are bundled inside the app — the interface renders perfectly with zero internet on first launch.

---

## 🌟 3 Core Downloading Features

1. **⚡ FAST DL (One-Tap Circle Action)**
   - Simply copy any video link from any app and tap the centerpiece **Circular Crystal Vortex Button**.
   - Auto-grabs from your clipboard and immediately starts downloading the video in the highest available quality without any modals or popups.

2. **🔍 Manual Download (Inspect & Choose Quality)**
   - Tap `[📋 Paste]` to pull from your clipboard.
   - Tap `Inspect Available Qualities` to inspect media metadata (thumbnail preview, duration, title, uploader).
   - Pick from the qualities that actually exist for that video, or Audio Only (MP3).
   - Tap `Download Video` to begin downloading in your chosen quality.

3. **↗ THE OMNI GRAB (System Share Receiver)**
   - In any platform app (TikTok, YouTube, Instagram, Facebook, X, Reddit), tap **Share** → **OmniDownloader**.
   - Automatically downloads the video in best quality in the background with progress notifications and 1-tap Play/Share actions upon completion.

---

## 🚫 100% Platform Watermark-Free

- **TikTok**: Direct HD CDN extraction (`hdplay`) bypasses TikTok's bouncing watermark and end-screen logo completely.
- **Instagram**: Clean MP4 source streams for Reels, Stories, and Video Posts without Instagram branding.
- **YouTube & Shorts**: Full source quality extraction without stamps.
- **Facebook, X (Twitter), Reddit, Pinterest**: Clean CDN streams saved directly to your phone's Gallery / Movies folder.

---

## 💎 Features & Architecture

- **Glassic Luxury Design**: Frosted glass surfaces, neon cyan halo accents, tactile controls.
- **Media Vault**: Real on-device library with thumbnails, playback, sharing and deletion.
- **Dual-Engine Core**: Built on Capacitor 6, Android Native Java bridges, and Chaquopy on-device Python media extraction.
- **Resilient Fallback Cascade**: Multiple-tier stream selection with platform-aware retries ensuring downloads rarely fail.
- **Offline-First UI**: All web assets (Tailwind, fonts) bundled locally.

---

## 📦 Releasing a New Version

1. Bump `versionCode` / `versionName` in `android/app/build.gradle`.
2. Commit and tag: `git tag v2.2.0 && git push origin main --tags`.
3. GitHub Actions builds the signed APK and attaches it to the release automatically.
4. Existing users tap **Check Updates** in the app — download progress bar, one-tap install.

### Signing (CI)

Set these repository secrets (Settings → Secrets → Actions):
- `KEYSTORE_BASE64` — base64 of the release keystore
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Local builds read the same values from `android/keystore.properties` (gitignored — never commit it).
