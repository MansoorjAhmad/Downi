# DOWNI

> **Grab any video. One tap. Zero clutter.**

DOWNI is a dark, fast Android downloader for video and audio. Copy a link, paste a link, or share
one to DOWNI — the file lands in your gallery, watermark-free, up to 1080p.

[Releases](https://github.com/MansoorjAhmad/Downi/releases) ·
[Changelog](CHANGELOG.md) · [Device test matrix](DEVICE_TEST.md)

---

## Three ways to grab

| | How it starts | What you get |
|---|---|---|
| **One-tap** | Copy a link anywhere. The banner already knows the title, the platform and the real quality list — tap **Grab now**. | The file downloads in the chosen lane. If that lane fails, DOWNI falls back down the list instead of ending in an error. |
| **Inspector** | Paste a link, or hit **Inspect** on the banner. | The thumbnail, the title and every real quality the link offers — you pick. |
| **DowniDrop** | Share → **DOWNI**, from any app. | A silent background grab. You never leave the app you were in. |

**Nothing downloads without a tap.** DOWNI never grabs a link you did not ask for.

## What it does

- **YouTube, TikTok, Instagram, X, Facebook, Reddit, Pinterest** — and anything that resolves to a
  direct media link.
- **Up to 1080p on YouTube** — the video and audio streams are downloaded separately and merged on
  the device with Android's own MediaMuxer. No ffmpeg, no client spoofing.
- **Audio only** — MP3 / M4A.
- **3 parallel downloads**, 6 queued behind them, with live progress, speed and ETA in the app and
  in the notification.
- **Playlists** — paste a playlist link and DOWNI grabs every video in it, one after another.
- **Engine health and network diagnostics** in Settings, so "it didn't work" has an answer.
- **In-app updater** — Settings → Updates.

## 100% watermark-free

| Platform | Method |
|----------|--------|
| **YouTube & Shorts** | yt-dlp, up to 1080p muxed on device |
| **Instagram** | Clean MP4 — Reels, Stories, Posts |
| **TikTok** | Direct HD CDN (`hdplay`) — no bouncing watermark |
| **Facebook** | Clean CDN stream |
| **X (Twitter)** | Native video extraction |
| **Reddit / Pinterest** | Clean CDN streams |

## The app asks for very little

| Permission | Why |
|---|---|
| Internet | That is the whole job. |
| Notifications | Progress and completion. Asked for on your first grab, not at launch. |
| Foreground service | Keeps a download alive when you leave the app. |
| Install packages | The in-app updater installs the next version. |

**No accessibility access. No media read. No "manage all files".** A downloader that watches your
screen and reads your whole gallery is a hard sell. One that asks for a notification and nothing
else is easy.

## Install

Grab the APK from [Releases](https://github.com/MansoorjAhmad/Downi/releases/latest), allow
installs from your file manager, and install it. It updates over any earlier version — the signing
key is unchanged, so your settings and your downloads stay.

Requires Android 7.0 (API 24) or newer.

## Build it yourself

```bash
npm install
npx cap sync android
cd android && ./gradlew assembleRelease
```

- `npx cap sync android` is enforced by the `preBuild` guard, so the bundled UI can never ship
  stale.
- `versionCode` / `versionName` live in `android/app/build.gradle`.
- Local release builds read `android/keystore.properties` (gitignored). CI uses the
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets.
- `tools/_tabcheck.js` fails the build if a tab switch ever leaks a view or leaves two nav items
  lit — the one regression a browser run cannot see.
- `DEVICE_TEST.md` is the on-device matrix, and **no release ships before it passes on a real
  phone**.

## Architecture

- **Capacitor 6** for the shell, **Chaquopy** for an on-device Python engine, **yt-dlp 2026.8.19**
  for extraction. No server, no account, no tracking.
- **The engine is on the phone.** Resolution, the format cascade, the merge and the queue all run
  locally — the app only needs a network that can reach the platform.
- **One hand-authored stylesheet** (`www/assets/css/downi.css`) is the whole design system. No CSS
  framework, no font CDN, no third-party script — the app is self-contained and works offline.
- **Three tabs**: Grab, Queue, Settings. The Queue shows every grab, live and finished.

## The brand

Obsidian `#090B0D`, Graphite `#151A1E`, Electric Lime `#C6FF3D`, Ice White `#F4F7F8`. Dark only, by
design — there is no light mode and no AMOLED switch, on purpose.

---

<div align="center">
  <sub>BUILT <b>DIFFERENT</b>. DESIGNED TO <b>LAST</b>.</sub>
</div>
