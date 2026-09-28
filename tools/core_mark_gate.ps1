# V3.2 Core Phase A - the mark gate (cell K-A2): the sheet-2 mark, rendered on the phone.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_mark_gate.ps1
#   powershell ... -File tools\core_mark_gate.ps1 -At 500,1000 -Scales 0.42,0.53,0.56,0.63,0.70
#
# Why this exists: the mark is lifted pixel-exact from design sheet 2 by
# tools/core_mark_from_sheet.py, but *how big* it sits in the Core is a judgement the owner makes
# by eye. So the Core is put at a known spot and shot twice:
#   1. a `mark <scale>` sweep at one size  -> pick the scale that matches sheets 3/4/6,
#   2. the 48 / 56 / 64 dp row at the bake candidate -> the legibility gate.
# Shots land in test_out\core_visual\ and every step is appended to _core_mark_gate.log as it
# happens, so a caller's timeout can never hide where it stopped.
#
# adb is invoked directly, never through `cmd /c` wrappers: on this box the wrapped form stalled
# silently (the lesson already written into tools\core_gate.ps1).
param(
    [string]$Scales = '0.42,0.53,0.56,0.63,0.70',
    [string]$Bake   = '0.63',
    [string]$State  = 'detected',
    [int]$Size      = 64,
    [string]$At     = '500,1000',
    [switch]$Default        # send NO `mark` command at all: proves what the baked
                            # CoreHost.DEFAULT_MARK_SCALE draws (the shipping default)
)

$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root 'test_out\core_visual'
$log   = Join-Path $root '_core_mark_gate.log'
$tmp   = Join-Path $env:TEMP 'core_mark_cmd.txt'

function Say($m) {
    Add-Content -Path $log -Value ((Get-Date -Format 'HH:mm:ss') + ' ' + $m)
    Write-Host $m
}

New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((& $adb devices) -join ' ') -replace "`r?`n", ' '))
if (-not (((& $adb devices) -join ' ') -match "`tdevice")) {
    Say 'NO DEVICE - plug the phone in (or run tools\wifi_adb.ps1) and try again'
    exit 1
}

# Writes one command file, then pushes it; the service's poller consumes and deletes it.
# Multi-line bodies are fine - the poller runs them top to bottom in one pass.
function Cmd([string]$body) {
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    $r = (& $adb push $tmp "$phone/core.cmd") 2>&1
    Say ('cmd [' + ($body -replace "`r?`n", '; ') + '] -> ' + ($r | Select-Object -Last 1))
}

function Shot([string]$name) {
    $f = Join-Path $out ($name + '.png')
    & $adb shell screencap -p /sdcard/shot.png | Out-Null
    & $adb pull /sdcard/shot.png $f | Out-Null
    Say ('shot ' + $name + ' ok=' + (Test-Path $f))
}

# The vivo ROM wipes `enabled_accessibility_services` on its own (a vendor force-stop does it),
# which kills the Core's window with the service - run 2 of this gate lost every shot after boot
# that way. So: re-arm before every shot and re-send the whole command set, which is idempotent.
function A11yOk { ((& $adb shell settings get secure enabled_accessibility_services) -join '') -match 'FetchSpike' }

function EnsureA11y {
    if (A11yOk) { return }
    Say 'A11Y WIPED - re-arming'
    & $adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.FetchSpikeService" | Out-Null
    & $adb shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 3
    Say ('A11Y now=' + ((& $adb shell settings get secure enabled_accessibility_services) -join ''))
}

# Re-arm if needed, send the commands, wait for the frame, then shoot. `show` is included every
# time: after a re-arm the Core has to be attached again before anything else can be seen.
function Shoot([string]$name, [string]$body) {
    EnsureA11y
    Cmd "show`n$body"
    Start-Sleep -Milliseconds 900
    Shot $name
}

$atParts = $At.Split(',')
$ax = [int]$atParts[0]
$ay = [int]$atParts[1]

# size -> at -> mark -> state: a size change rebuilds the window's LayoutParams, so `at` and the
# rest must follow it. `-Default` leaves the mark line out entirely.
function Body([int]$dp, [string]$markScale) {
    $b = "size $dp`nat $ax $ay"
    if ($markScale) { $b = $b + "`nmark $markScale" }
    $b + "`nstate $State"
}

$tag = if ($Default) { 'def' } else { 's{0:d3}' -f [int][math]::Round([double]$Bake * 100) }

Shoot "core_mark_boot_${Size}dp" (Body $Size ($(if ($Default) { '' } else { $Bake })))

if (-not $Default) {
    foreach ($s in $Scales.Split(',')) {
        $t = $s.Trim()
        if (-not $t) { continue }
        $st = 's{0:d3}' -f [int][math]::Round([double]$t * 100)
        Shoot "core_mark_${st}_${Size}dp" (Body $Size $t)
    }
}

foreach ($dp in 48, 56, 64) {
    Shoot "core_mark_${tag}_${dp}dp" (Body $dp ($(if ($Default) { '' } else { $Bake })))
}

Cmd "mark $Bake`nsize $Size`nat $ax $ay`nstate idle"

Say ('a11y=' + ((& $adb shell settings get secure enabled_accessibility_services) -join ''))
Say 'DONE'
Write-Host ''
Write-Host "Screens in $out - compare core_mark_* against design sheet 2 (mark) and sheets 3/4/6 (size)."
