# 📱 DOWNI Device Test Matrix — The Law's Gate

`READ_THIS_BEFORE_UPGRADE.md` §5: **no release ships before this passes on a real phone.**
Fill it in every time the extraction layer changes, and after any UI/wiring release like a
hardship check. Mark ✅ / ❌ per cell.

## 1. Core matrix (mandatory)

| Platform | Vortex (1-tap) | Inspector (pick quality) | DowniDrop (share → DOWNI) |
|---|---|---|---|
| **YouTube** | ☐ | ☐ (verify 720p plays with sound) | ☐ |
| **Instagram** (public reel) | ☐ | ☐ | ☐ |
| **TikTok** | ☐ (HD, no watermark) | ☐ | ☐ |

For each ✅: file exists in Vault, size > 0 KB, plays with audio, correct format (.mp4/.m4a).

> Tip: pick SHORT clips for the YouTube cells. YouTube now serves adaptive-only streams, so a 60fps
> 4K demo (e.g. Big Buck Bunny) is a ~258 MB 1080p download — on a slow link that looks like a
> stalled engine. A 19-second clip (e.g. "Me at the zoo") proves the same path in seconds.

## 2. v3.0 features matrix

| Feature | Check |
|---|---|
| True 1080p merge | ☐ YouTube Inspector → 1080p Full HD → video plays with sound |
| 1080p on a VP9-only video | ☐ merges on Android 10+ / honest error on older |
| Playlist Grab-all | ☐ paste a playlist → banner → 2+ videos queue and complete |
| Vault | ☐ downloads appear (permission asked once, on first Vault open) |
| Vault player | ☐ play, seek, ±10s, prev/next, position memory |
| Custom save folder | ☐ Settings → Save location → both in-app and DowniDrop land there |
| Share via text selection | ☐ select a link in Chrome → DOWNI in menu → Inspector opens |
| Updater | ☐ Settings → Check Updates finds the latest release |

## 3. Regression sweep (after any engine touch)

- ☐ Cancel mid-download (in-app + DowniDrop) leaves no `.part` files and no stuck notification
- ☐ Airplane mode → honest error, no crash
- ☐ 3 parallel downloads + 4th queued → queue caps politely

> Sign and date here when green: ______________
