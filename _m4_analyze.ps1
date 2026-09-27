# V3.3 Core 2.0 - the M4 C5 motion verdict, run detached (869 frames take ~45 s).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File _m4_analyze.ps1
#
# Writes the analyzer's whole stdout to _m4_frames.log in the repo root; the audit's own exit code is
# appended as a final line so a poller can tell "finished" from "still measuring".
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot                     # this script lives in the repo root
Set-Location $root
& python tools/core_state_audit.py frames test_out/core_motion/frames --at 452,1080 `
    --size-dp 64 --density 2.75 --fps 30 --fixed *> (Join-Path $root '_m4_frames.log')
Add-Content -Path (Join-Path $root '_m4_frames.log') -Value ("EXIT=" + $LASTEXITCODE)
