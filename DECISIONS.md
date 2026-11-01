# DOWNI — Permanent Decisions

This file records owner rulings that are **closed**. A future session, contributor, or AI agent
must not re-open them, "improve" them, or reintroduce the removed work under a new name. If a
change appears to require reversing one of these, stop and ask the owner first.

---

## D-001 — Pause / resume is removed and permanently banned

**Status:** CLOSED — removed in v3.8.0 (versionCode 60).
**Owner ruling:** *"I am completely removing those two shits from our app… I don't need that two
Pause / Resume shits… Add it in our file so in future the next AI don't work on it."*

### What was removed in 3.8.0

- Notification **Pause** and **Resume** actions (`shared_pause`, `shared_resume`,
  `shared_resume_latest`).
- Queue **▶ Resume** control and `resumeOneDownload()` in `www/index.html`.
- `DowniEnginePlugin.resumeDropJob()`.
- `pausedJobs`, `pausedGrabs` / `PAUSED_KEY`, `handlePaused()`, `persistPausedRemove()`,
  `resumePausedJob()` in `DowniDownloadService`.
- `DownloadProgressListener.isPaused()` and `supportsPause()`.
- Python `PausedError`, all `isPaused()` polls, and the `.part` / `continuedl` continuation path.
- Resume-on-process-death behaviour: an interrupted grab is now discarded (stale registry entry
  and bytes deleted), never resumed.

### The rule

- **Do not reintroduce pause/resume under any name.** This includes "resume from where you left
  off", "continue download", "hold", "suspend", `.part`-based resumption, persisted resumable job
  state, or a notification action that stops a transfer without cancelling it.
- A cancel is final. A cancelled or interrupted transfer **restarts from zero** on the next grab.
- `nopart` stays `True` and `continuedl` stays `False` in `downloader.py`.
- `READ_THIS_BEFORE_UPGRADE.md` §7.1 and `GEMINI.md` carry the same ban.

**Why:** the owner does not want the feature. It also caused the N11/N12 truth defects (rows that
claimed a live state the service was not in), and it added a second state machine to the download
path for no user-requested benefit.

---

## D-002 — Fonts are permanently frozen

**Status:** CLOSED.
DOWNI Sans (modified Hubot Sans, OFL) and JetBrains Mono are final. No font, type-scale, or brand
typography change in any release. Do not "improve" or replace them.

---

## D-003 — The Anti-Repost Shield stays

**Status:** CLOSED — user-facing feature.
It must not be removed or disabled by default.

### What the code does:
- For MP4 containers with 1 H.264 video track (profile <= High) and 0 or 1 AAC audio track (`VideoUniquifier.java` lines 158–190), it performs a software sample-by-sample remux via `android.media.MediaExtractor` and `android.media.MediaMuxer` (`VideoUniquifier.java` lines 202–307), preserves the orientation hint from `KEY_ROTATION` (`VideoUniquifier.java` lines 227–235), and appends an ISO `free` box with 48 bytes of entropy from `SecureRandom` (`VideoUniquifier.java` line 297, `IsoBmff.java` lines 85–98).
- For non-remuxable containers/codecs or upon remux failure, it copies input bytes verbatim and appends an ISO `free` box (MP4) or EBML `Void` element (WebM) (`IsoBmff.java` lines 175–222). For MP4 files, it overwrites the `creation_time` and `modification_time` timestamps in the `moov.mvhd` atom (`IsoBmff.java` lines 229–296). It does not parse or strip individual metadata tags.
- Post-process output validation checks track count, dimensions, duration tolerance (±150ms), and checks that the first video sample has `MediaExtractor.SAMPLE_FLAG_SYNC` set via `android.media.MediaExtractor` (`VideoUniquifier.java` lines 318–375). It does not use `MediaMetadataRetriever` and does not decode video frames.
- If validation fails, original files are preserved unmodified (`VideoUniquifier.java` lines 146–149).
- Settings toggle has an explanatory confirmation sheet when switching off to prevent accidental disabling.
- **Platform Effect:** The effect of these modifications on any platform's ingest, processing, duplicate detection, or distribution is completely untested. The code makes no claim to defeat, bypass, or evade detection on any service.

---

## D-004 — Out of scope, permanently

- Play Store track (AAB, targetSdk 35+, listing assets).
- x86_64 ABI and ABI splits.
- TikTok photo/carousel downloads.
- Cloud Boost / external relay.
- Light theme / theming rework.

These were rejected by owner ruling. Do not re-open them as "quick wins".

---

## D-005 — Single source of truth for app version

**Status:** CLOSED — implemented in v3.8.0 (versionCode 60).
`android/app/build.gradle` is the single source of truth for `versionName` and `versionCode`.
Native plugins read from `BuildConfig` generated from `build.gradle`, eliminating hardcoded fallbacks.
The web UI dynamically queries the native app info rather than storing hardcoded version strings.
Enforced in CI via `ci/verify_version_parity.py`.

