# V3.3 Core 2.0 - M8's ambient budget, read from the service's own account of the stage.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_ambient_probe.ps1
#
# WHY THIS EXISTS NEXT TO core_idle_cost.ps1. That cell counts FRAMES, which is the instrument the
# budget's claim is phrased in ("the Core stops drawing once the window is over") - but a frame
# counter alone cannot tell three faults apart that all end in a flat line: the window really ended,
# the composition never started, or the Core was never shown (no geometry, so no stage at all).
# `stage` separates them: it reports the file the stage holds, its live frame, whether its animator
# still runs, how many times it has been drawn, and - M8's own addition, see CoreHost.stageNote -
# `amb=1` while the looping READY look is still breathing and `held=1` while the window is invisible.
#
# THE PASS: SHOW + DETECTED probed in the same poller tick (the file at frame 0: `amb=1 run=true`),
# again at +3 s (still inside the 6 s window), again at +7 s (`amb=0 run=false` - the window ended and
# holdAmbient paused the drawable), then HIDE + probe (`held=1 run=false`). The last one is the half a
# frame counter can never show: this ROM never removes the overlay window (DowniCore.hide() only sets
# the view GONE, because re-adding it loses touch), so a hidden Core used to keep computing 60 fps
# frames for a view the framework had stopped repainting.
#
# Every push is verified SERVED before the next one goes out (see core_motion_probe.ps1's note: a push
# the poller misses reads exactly like app breakage). Output: `_core_ambient_probe.log` plus
# `probe.log` next to the still.
param(
    [string]$Out = 'test_out\core_ambient_probe'
)
$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root $Out
$log   = Join-Path $root '_core_ambient_probe.log'
$sw    = [Diagnostics.Stopwatch]::StartNew()

function Say($m) {
    $line = (Get-Date -Format 'HH:mm:ss.fff') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m
    Add-Content -Path $log -Value $line
    Write-Host $m
}
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_ambient.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function CmdCount([string]$first) { [int](Sh "shell grep -c 'CORE_CMD cmd=$first' $phone/$script:f") }
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }
function PushServed([string]$body, [string]$first) {
    $before = CmdCount $first
    for ($try = 1; $try -le 3; $try++) {
        Push $body
        for ($i = 0; $i -lt 14; $i++) {
            Start-Sleep -Milliseconds 250
            if ((CmdCount $first) -gt $before) { return $true }
        }
        Say ('  !! ' + $first + ' not served in 3.5 s - re-pushing (try ' + ($try + 1) + ')')
    }
    Say ('  !! PUSH LOST: ' + $first)
    return $false
}
function Probe { PushServed 'stage' 'stage' | Out-Null }
function Shot([string]$name) {
    $f = Join-Path $out ($name + '.png')
    Sh 'shell screencap -p /sdcard/ambient.png' | Out-Null
    Sh "pull /sdcard/ambient.png `"$f`"" | Out-Null
    if (Test-Path $f) { Say ('  still ' + $name + ' ' + (Get-Item $f).Length + 'B') } else { Say ('  still ' + $name + ' FAILED') }
}
# ---------- bind (never by writing the setting - see core_shots_live.ps1) ----------
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
    # one file, three lines: show, THEN position, then size - and it must be SERVED before any state
    # is driven, because a Core that was never shown has no geometry and would log `stage=null`
    if (-not (PushServed "show`nat 452 1080`nsize 64" 'show')) { Say 'FAIL: show never served'; return $false }
    Start-Sleep -Milliseconds 1500      # let it lay out: onSizeChanged is what gives the stage a size
    return $true
}

New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')) + '  window=6000ms  cadence=42ms  probe=stage')
if (-not (Test-Path $adb)) { Say 'adb not found'; exit 1 }
if (-not (Launch)) { Say 'DONE'; exit 1 }

# the same tick as the state change: the file is caught at frame 0, running
Say 'step detected    <state detected | stage>'
PushServed "state detected`nstage" 'state' | Out-Null
Start-Sleep -Milliseconds 3000
Say '  +3.0s  (inside the window: still breathing)' ; Probe
Start-Sleep -Milliseconds 4000
Say '  +7.0s  (past the window: the budget must have ended it)' ; Probe
Shot 'ambient_detected_settled'

Say 'step hide        <hide | stage>'
PushServed "hide`nstage" 'hide' | Out-Null
Start-Sleep -Milliseconds 1000
Say '  +1.0s  (invisible: the Core must be parked)' ; Probe
# ---------- the service's own account of the pass ----------
Say ''
Say 'phone log (CORE_CMD / CORE_STATE / CORE_STAGE, in the phone''s own order):'
# NB: no `|` in a grep that travels through `cmd /c` - cmd does not honour single quotes (see
# core_motion_probe.ps1). Multiple -e are safe.
$lines = @((Sh "shell grep -e CORE_CMD -e CORE_STATE -e CORE_STAGE $phone/$script:f") -split "`n") |
    Where-Object { $_.Trim() }
$dump = Join-Path $out 'probe.log'
$lines | Set-Content -Path $dump -Encoding ASCII
$lines | ForEach-Object { Say ('  ' + $_) }

Say ''
Say 'stage readings (file / frame / running / ambient / held / draws):'
foreach ($l in $lines) {
    if ($l -notmatch 'CORE_STAGE ') { continue }
    $t = ($l -split ' ')[0]
    $note = $l.Substring($l.IndexOf('CORE_STAGE ') + 11)
    $file = if ($note -match 'stage=(\S+)') { $matches[1] } else { '?' }
    $fr = if ($note -match '\bf=(\d+)') { $matches[1] } else { '-' }
    $run = if ($note -match 'run=(\w+)') { $matches[1] } else { '-' }
    $amb = if ($note -match 'amb=(\d)') { $matches[1] } else { '-' }
    $held = if ($note -match 'held=(\d)') { $matches[1] } else { '0' }
    $dr = if ($note -match 'draws=(\d+)') { $matches[1] } else { '-' }
    Say ('  ' + $t + '  ' + $file.PadRight(16) + ' f=' + $fr.PadRight(4) + ' run=' + $run.PadRight(6) +
         ' amb=' + $amb.PadRight(2) + ' held=' + $held.PadRight(2) + ' draws=' + $dr)
}

$readings = @($lines | Where-Object { $_ -match 'CORE_STAGE ' })
$armed   = @($readings | Where-Object { $_ -match 'amb=1' -and $_ -match 'run=true' })
$ended   = @($readings | Where-Object { $_ -match 'amb=0' -and $_ -match 'run=false' })
$parked  = @($readings | Where-Object { $_ -match 'held=1' })
Say ''
Say ('CLAIMS  armed(amb=1 run=true)=' + $armed.Count + '  ended(amb=0 run=false)=' + $ended.Count + '  parked(held=1)=' + $parked.Count)
if (-not $armed) { Say '  FAIL: the looping look never reported an armed window - check the show-served line above' }
if (-not $ended) { Say '  FAIL: no reading past the window shows amb=0 run=false - the budget did not end it' }
if (-not $parked) { Say '  FAIL: hide() never reported held=1 - a hidden Core is not being parked' }
$unb = Sh "shell grep -c SERVICE_UNBIND $phone/$script:f"
Say ('elapsed=' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's  SERVICE_UNBIND=' + $unb)
Say ('output in ' + $out + '   (probe.log is the evidence; the still is the eye half)')
Say 'DONE'