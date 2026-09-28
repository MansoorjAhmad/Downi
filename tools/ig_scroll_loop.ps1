# ig_scroll_loop.ps1 — simulate an owner actively using Instagram Reels (drives Instagram to the
# foreground and scrolls it on a fixed cadence).
#
# WHY: ABE's kill threshold appeared to move with activity (159 s / ~220 s / 328 s across runs),
# and the only thing that matters for the Fetcher is whether we survive while the owner is
# actually watching videos. An unattended soak only proves the idle case, which we already lost.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\ig_scroll_loop.ps1 -Minutes 8
# Pair it with soak_watch.ps1 so the two logs line up:
#   tools\soak_watch.ps1 -Minutes 9 -Out <repo>\_soak_active.log
# Then: tools\fetch_diag.ps1 -Pull and look for action.vivo.powercontrol / am_kill.

param(
    [int]$Minutes = 8,
    [int]$EverySec = 10
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$adb  = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$pkg  = 'com.instagram.android'

if (-not (Test-Path $adb)) { Write-Error "adb not found at $adb"; exit 1 }

# Bring Instagram up first — without a foreground owner there is nothing to simulate.
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 *> $null
Start-Sleep -Seconds 3

$deadline = (Get-Date).AddMinutes($Minutes)
$n = 0
while ((Get-Date) -lt $deadline) {
    # Big upward swipe = advance to the next Reel (y 1700 -> 600, 400 ms, held shape of a flick)
    & $adb shell input swipe 540 1700 540 600 400 *> $null
    $n++
    Start-Sleep -Seconds $EverySec
}
Write-Output "scrolled $n times over $Minutes min"
