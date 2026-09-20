# READ THIS BEFORE UPGRADE

## 1️⃣ Proven Working Baseline (v2.6.2 – restored v1.3.6 core)

- **YouTube extraction**
  - Uses **yt‑dlp** with a minimal options set:
    ```python
    YoutubeDL({
        "ca_certs": certifi.where(),
        "outtmpl": os.path.join(target_dir, "%(title).120B-%(id)s.%(ext)s"),
        "windowsfilenames": True,
        "overwrites": True,
        "nopart": True,
        "retries": 3,
        "socket_timeout": 30,
        "progress_hooks": [_progress_hook],
        # format cascade – pure mp4 streams, no client spoofing
        "format": "best[height<=1080][ext=mp4][vcodec!=none]/best[height<=720][ext=mp4][vcodec!=none]/best[height<=480][ext=mp4][vcodec!=none]/best[ext=mp4][vcodec!=none]/best",
        "audio_format": "bestaudio[ext=m4a]/bestaudio/best",
    })
    ```
  - No `extractor_args` such as `player_client` and **no forced Android‑mobile User‑Agent**.
  - Directly passes `certifi.where()` for SSL verification – no monkey‑patched `ssl._create_unverified_context`.

- **Instagram extraction**
  - Calls the built‑in `instagram` extractor from `yt‑dlp` with the same simple options as above.
  - Relies on public, unauthenticated endpoints; **does not attempt to reuse a logged‑in session or cookies**.
  - Uses the same `outtmpl`, `retries`, and `socket_timeout` values, ensuring consistent behaviour with YouTube.

- **TikTok extraction**
  - Uses a lightweight direct‑API (`tikwm.com`) implementation that downloads the raw CDN URL.
  - Mirrors the same retry/timeout settings.

> **Result:** All three platforms work from **all three entry points** – Vortex (Fast DL button), Inspect (manual quality picker), and DowniDrop (share‑intent receiver).

---

## 2️⃣ What Broke It in v2.6.x and Why

| Change | Where it was introduced | Technical impact |
|--------|------------------------|------------------|
| **`player_client` extractor args** (e.g. `['ios','tv','web_safari']`, `['android_vr']`, `['tv_embedded']`) | `downloader.py` (v2.6.x) | Forces YouTube to request *GVS* “po_token” streams that only work with Google‑verified mobile clients. The Android‑mobile UA we forced prevented the token from being accepted, causing YouTube downloads to return *403* / empty output. |
| **Forced Android Mobile User‑Agent** (header override) | Same file, before calling `YoutubeDL` | Instagram detects the UA as a mobile app and returns a *login‑required* JSON payload or empty media list, so all Instagram entry points fail. |
| **Aggressive URL stripping / segmented multi‑connection streaming** (custom connection manager) | Complex download engine block (~800 lines) | Introduced race conditions and premature socket closures; YouTube and Instagram streams were truncated or aborted, leading to silent failures. |
| **Monkey‑patched `ssl._create_unverified_context`** | Early init code in v2.6.x | Bypassed proper certificate verification, which later conflicted with the `ca_certs` option and caused SSL handshake failures on newer devices. |

These changes collectively broke the previously reliable **v1.3.6** core.

---

## 3️⃣ Instagram Session / Cookie Login Feature – **DO NOT RE‑INTRODUCE**

- A prototype was built that harvested a real logged‑in Instagram session (cookies & CSRF token) and replayed it in automated download requests.
- **Risks:**
  - Exposure of the user’s personal Instagram credentials to the app’s process.
  - Violates Instagram’s Terms of Service – automated replay of a logged‑in session is explicitly prohibited.
  - Can lead to account bans or legal issues.
- The feature was **removed** in v2.6.2 and must remain removed. Even if framed as “optional” or “user‑enabled”, it must **never** be reimplemented.

---

## 4️⃣ Cloud Boost Relay Issue – What Went Wrong & Future Fix

- **Problem:** In v2.6.x the Cloud Boost fallback silently failed. The code attempted to auto‑retry via a remote relay but:
  1. No health‑check was performed; the relay could be unreachable.
  2. Failure bubbled up as an unhandled exception, crashing the app instead of gracefully falling back to the local extractor.
- **Fix (implemented in v2.6.2):**
  - Remove the Cloud Boost path entirely for on‑device builds.
  - If a future Cloud Boost is needed, implement a **robust wrapper** that:
    - Performs a **quick ping / health‑check** before invoking the relay.
    - Falls back to the **local downloader** on any non‑200 response or timeout.
    - Reports the fallback to the UI with a non‑intrusive toast, never aborting the whole download flow.

---

## 5️⃣ Mandatory Test Protocol Before Any Extraction‑Layer Change

1. **Prepare a clean device build** (no leftover caches).
2. **Run the following matrix** (all tests must pass):
   - **Platforms:** Instagram, YouTube, TikTok.
   - **Entry Points:**
     - **Vortex** (Fast DL button)
     - **Inspect** (manual quality picker)
     - **DowniDrop** (share‑intent receiver)
   - **Checks for each combination:**
     - Download completes without error.
     - Resulting file size > 0 KB and matches the expected format (`.mp4` for video, `.m4a` for audio).
     - No crashes or unhandled exceptions in the Java‑Python bridge.
3. **Automated script** (optional but recommended):
   - Use a headless Android test runner (e.g., `adb shell am instrument`) that programmatically triggers each entry point with a known test URL.
   - Verify exit codes and file existence on the device storage.
4. **Only after the full matrix passes** should a change be merged.

---

### 📌 Bottom Line
- Keep the **simple, certifi‑verified yt‑dlp options** as the source of truth.
- Never re‑add client‑spoofing, forced UAs, aggressive streaming hacks, or Instagram session harvesting.
- If a Cloud Boost or any external relay is ever reconsidered, wrap it in a safe fallback layer with health checks.
- Always run the **full Instagram / YouTube / TikTok × 3 entry‑point test suite** before any modification.

*This file is a permanent reference; future contributors should read it before touching the download engine.*
