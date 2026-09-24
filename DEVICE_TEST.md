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

## 3. v3.1.0 matrix (The Polish Release — MANDATORY)

| # | Test | Check |
|---|---|---|
| 1 | **Cold-start DowniDrop** — force-stop DOWNI (Settings → Apps → DOWNI → Force stop), then share a TikTok link → DOWNI | ☐ toast appears instantly, app does NOT open, progress notification appears, file lands in Vault |
| 2 | Warm-share instant grab (app open in background) | ☐ same result, no Inspector |
| 3 | Rapid double-share | ☐ both files download (queued), both completion notifications, no overwrite |
| 4 | Cancel button on DowniDrop progress notification | ☐ download stops, no file in Vault, notification clears |
| 5 | Failure path — share an invalid/private link | ☐ failure notification with honest reason; tap → Inspector opens with the link |
| 6 | **Settings → DowniDrop → "Ask quality first"**, then share | ☐ RETIRED in v3.1.1 (owner ruling 2026-09-24): the Instant/Ask toggle was deleted after the instant column passed this matrix — shares always grab instantly. Re-check cell: Settings → DowniDrop shows one static "⚡ Instant grab" chip; a share never opens the Inspector |
| 7 | Quality memory — pick 720p for TikTok in Inspector once, then instant-share a TikTok link | ☐ grabs at 720p |
| 8 | Custom save folder set → instant DowniDrop share | ☐ file lands in the custom folder, appears in Vault |
| 9 | **Vault blink test** — open Vault, watch: downloads finishing, Rescan, tab switches | ☐ zero flicker; scroll position kept |
| 10 | Vault search — type quickly | ☐ grid updates once after pause (200ms), no per-keystroke flash |
| 11 | Vault long-press selection — enter, toggle 3 cards, exit | ☐ cards flip in place, no grid rebuild flash |
| 12 | New arrival — finish a download while Vault is open | ☐ only the new card fades in |
| 13 | Vault still shows only DOWNI downloads (v3.0.4 fold-in) | ☐ camera clips etc. stay out |

Device: vivo V2058, USB only.

