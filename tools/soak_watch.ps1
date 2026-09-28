# soak_watch.ps1 — liveness soak watcher for the DOWNI Fetcher spike.
#
# WHY: on the vivo V2058 a spike session dies silently (no crash, no SERVICE_UNBIND) and the
# device log buffers rotate within ~10 minutes, so a death can be missed entirely if nobody is
# looking at the right second. This appends one line every -IntervalSec for -Minutes, so the
# death time is on record even when the session is unattended.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\soak_watch.ps1 -Minutes 8
#   (add -Out C:\path\soak.log to name the log; default is <repo>\_soak_<HHmmss>.log)
#
# Read the result with:  Get-Content <Out>      (DEAD on any line = the session died that second)
#
# Companion: tools\fetch_diag.ps1 -Pull for the full forensic picture (crash buffer, am_kill,
# a11y state, spike-log tail) once soak_watch shows DEAD.

param(
    [int]$Minutes = 8,
    [int]$IntervalSec = 20,
    [string]$Out = ""
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$adb  = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$pkg  = 'com.omnidownloader.app'
$base = '/sdcard/Android/data/com.omnidownloader.app/files/fetch-spike/'

if (-not (Test-Path $adb)) { Write-Error "adb not found at $adb"; exit 1 }
if ($Out -eq "") { $Out = Join-Path $repo ('_soak_' + (Get-Date -Format 'HHmmss') + '.log') }

"# soak watch started $(Get-Date -Format 'HH:mm:ss') for $Minutes min (every ${IntervalSec}s) -> $Out" | Set-Content $Out

$deadline = (Get-Date).AddMinutes($Minutes)
$firstLog = ""

while ((Get-Date) -lt $deadline) {
    $ts   = Get-Date -Format 'HH:mm:ss'
    $alive = (& $adb shell ps -A 2>&1 | Select-String -Pattern $pkg)
    $state = if ($alive) { 'ALIVE' } else { 'DEAD ' }
    $a11y = ((& $adb shell settings get secure enabled_accessibility_services 2>&1) -join '').Trim()
    if ($a11y -eq 'null') { $a11y = 'WIPED' }

    # Heartbeat age is the truest liveness signal: a live process writes one every 30 s.
    $newest = ((& $adb shell ls -t $base 2>&1) | Where-Object { $_ -match 'spike_\d{8}-\d{6}\.log' } | Select-Object -First 1)
    $hb = ''
    if ($newest) {
        $n = $newest.Trim()
        if ($firstLog -eq '') { $firstLog = $n }
        $rebind = if ($n -ne $firstLog) { ' REBIND' } else { '' }
        $tail = (& $adb shell cat "$base$n" 2>&1 | Select-String -Pattern 'HEARTBEAT' | Select-Object -Last 1)
        $hb = if ($tail) { ' hb=' + ($tail.ToString().Trim().Split(' ')[0]) + $rebind } else { ' hb=none' + $rebind }
    }

    "$ts $state a11y=$a11y$hb" | Add-Content $Out
    Start-Sleep -Seconds $IntervalSec
}

"# soak watch ended $(Get-Date -Format 'HH:mm:ss')" | Add-Content $Out
