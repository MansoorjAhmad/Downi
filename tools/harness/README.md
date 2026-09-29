# tools/harness — the instruments that measured the link-grab

**Why these are here.** The scripts that produced the TikTok and Instagram link-grab readings were
written in a scratch working directory that is **not** under version control, while `DEVICE_TEST.md`
cited them by path. On 2026-09-29 that gap was closed for the scripts only: a wiped box or a second
machine would otherwise leave the report pointing at files nobody could produce.

**Provenance.** These are **byte-identical copies** of the scripts that were run on 2026-09-29
(sha256 verified on both sides at copy time — not "cleaned up", because a mirror that differs from
what actually ran is not evidence). The evidence *logs* are deliberately **not** committed; they are
bulky and transient, and they live beside the originals (names below).

**Verifying a hash.** The `sha256` column is of the **committed content with line endings normalised to
LF** (what git stores for these files), so it can be checked from a fresh clone:

```
git cat-file blob HEAD:tools/harness/_L_tiktok_control.ps1 > f.ps1
sha256sum f.ps1        # or: Get-FileHash f.ps1 -Algorithm SHA256
```

The copies that actually ran were the same bytes with CRLF endings — a difference in line endings only,
which is why hashing a Windows working copy will not reproduce the column above.

| File | What it is | sha256 |
|---|---|---|
| `_L_tiktok_control.ps1` | **The rig.** Five Core taps; finds the Core's own live window frame each attempt, dumps the feed before/after to fingerprint the video, taps, then exports the box through the UI and reads `CHAIN_LATENCY` | `712a273079da32e3c347c44f80b376b1a9e9a1047b31c81971270f1107418775` |
| `_J_export.ps1` | **The box exporter** the rig calls: asks the UI where the Export button is (it moves as the card grows), taps it, and refuses a stale `blackbox.txt` by comparing mtime | `ca896e0930999489e16b343f693568e3f130ca90af036002ea01a9a11ed4cbd7` |
| `_L_probe3.ps1` | Playing vs paused, a11y off: the pixel-hash MOVING/STILL discriminator | `f96d7f8fafe34e4ea4b625025cad98d13d9896e529addea1cba56bc7f2e0a33c` |
| `_L_probe4.ps1` | The two-axis test (a11y bound/unbound × playing/paused), 3 dumps per cell | `b2af16ef35e48f720aeed07fdd0a8b23163a1c9ebcdac3ad10fd13ad2cf84419` |
| `_L_probe5.ps1` | The last condition: dumps with the Core overlay **up**, reporting the dump's own message | `a3e5bf52cff11b9c79b5cabdf9fb26dac08bfa739fe4e3895562b6d56bc7bf56` |
| `_L_rig_selftest.ps1` | Runs the rig's own functions against the live device (Core, dump, fingerprint, arming) without tapping | `3005fa77b7e2545ad9e8888b7f59dc2b3019843d5d0d6e8f24f376b9d25d5574` |
| `_L_fp_test.ps1` | Feeds real dumped trees through the rig's `Fingerprint()`, including the fossil tree that caused the phantom SKIPs | `99f5550bf6dd82d30e0cf92ca10e8bc305e2412025e3135a385cf9ea21df7549` |
| `_core_arm.ps1` | The a11y armer, fixed in this pass: arms `DowniFetcherService` and proves the arm with the framework's bound-services list plus a polled Core window | `10764a85d0db977150b4a187ebff43fc48b9818bc046a7bd524cf4c49cb77f0b` |

**Results these produced:** `DEVICE_TEST.md` §0z-8-11 (five TikTok readings, median 1989 ms),
§0z-8-12 / §0z-8-13 (the harness truths), §0z-8-2 … §0z-8-10 (Instagram).

## Caveats — read before running

- **Not portable as-is.** Every script hardcodes the repo path for `adb.exe`, and
  `_L_tiktok_control.ps1` loads `_J_export.ps1` from the working directory it was written in
  (`$cline`). To run from a checkout, point `$cline` at this folder (that one variable drives the
  output dir, the log path and the exporter path).
- **Device-specific assumptions**, all measured on the vivo V2058: screen 1080×2275; the Core window
  at `[904,711][1080,887]` → tap `992,799`; the ROM dropping the a11y binding on its own; a Core
  window that can outlive the binding; `dumpsys media_session` answering `state=null`; `pidof`
  returning empty around transport hiccups; `Sh()` going through `cmd /c` (so a pipe inside it is
  parsed **locally** — do the filtering in PowerShell).
- **The dump is freshness-gated on purpose.** `uiautomator dump` prints its own message and writes
  nothing when it fails, so `Dump()` deletes the device file first, reads that message, retries three
  times, and accepts only a fresh tree containing the TikTok package. Removing that gate is how the
  four phantom SKIPs happened (DEVICE_TEST §0z-8-12).
- **How a measurement run is started:** detached via WMI `Win32_Process.Create`, then the log is
  polled — a tool timeout must not be able to kill a run mid-tap.
- **Evidence logs, not committed** (in the working directory): `_L\tiktok_control.txt` (the five
  readings, every dump line), `_J\tt1_box.txt` … `_J\tt5_box.txt` (one fresh 100-event box per tap),
  `_L\probe3.txt` / `probe4.txt` / `probe5.txt`, `_L\selftest.txt`, `_L\before_*.xml` / `after_*.xml`.
