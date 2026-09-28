# V3.2 Core Phase A - deterministic gate driver (logs every step as it happens).
# The first PowerShell driver used cmd /c wrappers; on this box several stale instances
# then raced for the adb server and it silently stalled. This one calls adb directly and
# writes its own log, so a timeout of the caller never hides where it stopped.
$root = Split-Path -Parent $PSScriptRoot
$adb  = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$log  = Join-Path $root '_core_states2.log'
$out  = Join-Path $root 'test_out\core_visual'
$tmp  = Join-Path $env:TEMP 'core_cmd.txt'
$phone = '/sdcard/Android/data/com.omnidownloader.app/files/fetch-spike'

function Say($m) { Add-Content -Path $log -Value ((Get-Date -Format 'HH:mm:ss') + ' ' + $m) }

$pkg = 'com.omnidownloader.app'

# This ROM wipes `enabled_accessibility_services` on its own and the overlay window dies with the
# service - a run then returns empty screenshots that look like a rendering bug (see
# `_gate_run2_a11y_wipe.log`). So re-arm before every push and every shot, the same guard
# tools\core_mark_gate.ps1 uses.
function A11yOk {
    ((& $adb shell settings get secure enabled_accessibility_services) -join '') -match 'FetchSpike'
}

function EnsureA11y {
    if (A11yOk) { return $false }
    Say 'A11Y WIPED - re-arming'
    & $adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.FetchSpikeService" | Out-Null
    & $adb shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 3
    Say ('A11Y now=' + ((& $adb shell settings get secure enabled_accessibility_services) -join ''))
    return $true
}

New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((& $adb devices) -join ' ') -replace "`r?`n", ' '))
if (-not (((& $adb devices) -join ' ') -match "`tdevice")) {
    Say 'NO DEVICE - plug the phone in (or run tools\wifi_adb.ps1) and try again'
    exit 1
}

function Send([string]$body) {
    if (EnsureA11y) { $body = "show`n$body" }   # the window died with the service: re-attach first
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    $r = (& $adb push $tmp "$phone/core.cmd") 2>&1
    Say ('push [' + ($body -replace "`r?`n", '; ') + '] -> ' + (($r | Select-Object -Last 1)))
}

function Grab([string]$name) {
    $f = Join-Path $out ($name + '.png')
    if (EnsureA11y) { Send 'show' ; Start-Sleep -Milliseconds 800 }
    & $adb shell screencap -p /sdcard/shot.png | Out-Null
    & $adb pull /sdcard/shot.png $f | Out-Null
    Say ('shot ' + $name + ' ok=' + (Test-Path $f))
}

$settled = 'idle', 'detected', 'dragging', 'paused', 'complete'
$transient = 'wake', 'pressed', 'snapped', 'resuming', 'completing', 'failed'

Send 'show'
Start-Sleep -Seconds 2
Grab 'core_00_show_idle'

foreach ($s in $settled) {
    Send ('state ' + $s)
    Start-Sleep -Milliseconds 900
    Grab ('core_state_' + $s)
}
foreach ($s in $transient) {
    Send ('state ' + $s)
    Start-Sleep -Milliseconds 250          # catch the transition itself, not its settled look
    Grab ('core_state_' + $s + '_mid')
    Start-Sleep -Milliseconds 800
    Grab ('core_state_' + $s + '_end')
}

Send "state progress`nprogress 0"
Start-Sleep -Milliseconds 900
Grab 'core_progress_000'
foreach ($v in 25, 50, 75, 100) {
    Send ("state progress`nprogress $v")
    Start-Sleep -Milliseconds 700
    Grab ('core_progress_' + $v.ToString('000'))
}

foreach ($dp in 48, 56, 64) {
    Send ("size $dp`nstate detected")
    Start-Sleep -Milliseconds 900
    Grab ('core_size_' + $dp + 'dp')
}
Send 'size 64'
Send 'state idle'
Start-Sleep -Milliseconds 700
Grab 'core_zz_idle_after'
Send 'hide'
Start-Sleep -Milliseconds 500
Grab 'core_zz_hidden'

Say ('a11y=' + ((& $adb shell settings get secure enabled_accessibility_services) -join ''))
Say 'DONE'
