# V3.3 Core 2.0 - M6's C6 recovery gate on the phone: the rose -> teal retry, in time.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_c6.ps1
#
# WHAT THIS PASS EXISTS FOR. Sheet C6 says "tap-to-retry does a press/rebound and transitions
# rose -> teal as it re-resolves", and until 2026-09-27 `core_retry.json` was shipped but wired to
# nothing: FAILED -> (tap) -> RESOLVING jumped straight to the resolver's orbit, so the
# acknowledgement never played. This pass drives the states the tap now produces (FAILED, then RETRY,
# then its own settle to RESOLVING) and records them, because the recovery is 36 frames at 60 fps =
# 600 ms: `screencap` costs ~0.7 s, which cannot see it, so every frame comes out of the video and
# `core_state_audit.py hues` reads the ring's colour and the art's size off each one.
#
# The bench drives the STATE; the tap that enters it is asserted in CoreLookTest/CoreTapActionTest
# and wired in DowniFetcherService's RETRY branch (core.setState(RETRY) before the chain starts).
param(
    [string]$Out = 'test_out\core_c6',
    [int]$Fps    = 30
)
$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$ffmpeg = Join-Path $root 'tools\ffmpeg.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root $Out
$log   = Join-Path $root '_core_c6.log'
$mp4remote = '/sdcard/core_c6.mp4'
$sw    = [Diagnostics.Stopwatch]::StartNew()

# name, the command file's body, and the hold. FAILED is held long enough to read as rose on its own
# (the ERROR pulse is 300 ms and the look holds), then RETRY plays its 600 ms and settles.
$steps = @(
    @{ n = 'failed'; cmd = 'state failed';  ms = 1800 },
    @{ n = 'retry';  cmd = 'state retry';   ms = 2600 },
    @{ n = 'idle';   cmd = 'state idle';    ms = 1200 }
)

function Say($m) {
    $line = (Get-Date -Format 'HH:mm:ss.fff') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m
    Add-Content -Path $log -Value $line
    Write-Host $m
}
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_c6.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }

$script:f = ''
$script:unbindBase = 0
function Launch {
    Sh "shell am force-stop $pkg" | Out-Null
    Start-Sleep -Milliseconds 1200
    Sh "shell am start -n $pkg/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 2600
    $script:f = NewestLog
    if ((Sh "shell grep -c CORE_READY $phone/$script:f") -notmatch '^[1-9]') {
        Say 'FAIL: no CORE_READY after launch'; return $false
    }
    $script:unbindBase = [int](Sh "shell grep -c SERVICE_UNBIND $phone/$script:f")
    Say ('bound: ' + $script:f + ' (unbind baseline ' + $script:unbindBase + ')')
    Push "show`nat 452 1080`nsize 64"
    Start-Sleep -Milliseconds 900
    return $true
}

New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $out 'frames') -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $out 'frames') | Out-Null
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')) + '  fps=' + $Fps)
if (-not (Test-Path $adb)) { Say 'adb not found'; exit 1 }
if (-not (Launch)) { Say 'DONE'; exit 1 }

$totalMs = ($steps | ForEach-Object { $_.ms } | Measure-Object -Sum).Sum
$limit = [int]([Math]::Ceiling($totalMs / 1000.0) + 3)
$segments = New-Object System.Collections.Generic.List[string]
$segments.Add('name,cmd,start_ms,end_ms,effect_ms')
Sh "shell rm -f $mp4remote" | Out-Null
Say ('recording: screenrecord --time-limit ' + $limit + ' s')
Start-Process -FilePath $adb -WindowStyle Hidden -ArgumentList @(
    'shell', "screenrecord --time-limit $limit --bit-rate 8000000 $mp4remote")
Start-Sleep -Milliseconds 1200

$recStart = $sw.Elapsed.TotalSeconds
foreach ($s in $steps) {
    $t = [int](($sw.Elapsed.TotalSeconds - $recStart) * 1000)
    Push $s.cmd
    Say ('step ' + $s.n.PadRight(8) + ' t=' + $t + 'ms  <' + $s.cmd + '>')
    $segments.Add($s.n + ',"' + $s.cmd + '",' + $t + ',' + [int]($t + $s.ms) + ',' + [int]($t + 450))
    Start-Sleep -Milliseconds $s.ms
}
$segments | Set-Content -Path (Join-Path $out 'segments.csv') -Encoding ASCII

$waitMs = [int](($recStart + $limit - $sw.Elapsed.TotalSeconds) * 1000)
if ($waitMs -gt 0) { Say ('waiting ' + $waitMs + 'ms for the recorder to finalise'); Start-Sleep -Milliseconds $waitMs }
$local = Join-Path $out 'core_c6.mp4'
Sh "pull $mp4remote `"$local`"" | Out-Null
if (Test-Path $local) {
    Say ('video ' + (Get-Item $local).Length + 'B -> extracting every frame at ' + $Fps + ' fps')
    & $ffmpeg -y -loglevel error -i $local -vf "fps=$Fps" -q:v 2 (Join-Path $out 'frames\f_%04d.png') 2>&1 |
        ForEach-Object { Say ('  ffmpeg ' + $_) }
    Say ('frames: ' + (Get-ChildItem (Join-Path $out 'frames\*.png')).Count)
} else { Say 'FAIL: no video pulled' }

$att = Sh "shell grep -c CORE_ATTACH $phone/$script:f"
$unb = Sh "shell grep -c SERVICE_UNBIND $phone/$script:f"
Say ('elapsed=' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's  CORE_ATTACH=' + $att + '  SERVICE_UNBIND=' + $unb)
@((Sh "shell grep CORE_STATE $phone/$script:f") -split "`n") | Select-Object -Last 8 |
    ForEach-Object { Say ('  log ' + $_) }
Say ('output in ' + $out + '   (segments.csv tells the analyzer which frame is which step)')
Say 'DONE'
