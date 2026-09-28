# V3.3 Core 2.0 - M5's C3 touch-physics contract on the phone: press, drag, edge snap.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_touch.ps1
#   powershell ... -File tools\core_touch.ps1 -NoRecord      (no video; stills + logs only)
#
# WHY INJECTED TOUCH + VIDEO. C3 is about what the finger feels: ~10 % compression on a press
# (~100 ms), a drag that follows, and a release inside 12 dp of an edge that magnetises and flattens
# the gel against it (~300 ms). The debug channel can command states, but it cannot press a Core -
# `adb shell input tap/swipe` injects at the system level and the overlay (TYPE_ACCESSIBILITY_OVERLAY,
# FLAG_NOT_TOUCHABLE cleared while interactive) receives it: verified on this ROM 2026-09-27, the tap
# logged `CORE_TOUCH down`/`up dragging=true` and the swipe left `CORE_MOVED x=123 y=1080` (123 px
# from the edge, so correctly NOT magnetised). `screencap` costs ~0.7 s per shot, which cannot resolve
# a 100 ms press, so the pass is recorded and every frame is measured.
#
# The drags are deliberately HORIZONTAL (y held at the Core's centre): the analyzer's tracker searches
# one row for the ring, which is exact, instead of fitting a disc to art that has no stroked rim.
param(
    [string]$Out = 'test_out\core_touch',
    [int]$Fps    = 30,
    [switch]$NoRecord
)
$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$ffmpeg = Join-Path $root 'tools\ffmpeg.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root $Out
$log   = Join-Path $root '_core_touch.log'
$mp4remote = '/sdcard/core_touch.mp4'
$cx0 = 540; $cy = 1168                      # the Core's centre for `at 452 1080` + `size 64` (176 px)
$sw    = [Diagnostics.Stopwatch]::StartNew()

# name, the gesture, and the hold after it. The press is one injected tap ON the Core's centre; the
# far drag ends >12 dp from every edge (the gel relaxes through the C3 section 2 swell, it does NOT
# snap); the edge drag ends 20 px from the left edge (it magnetises to x=0 and flattens on contact).
$steps = @(
    @{ n = 'rest';      kind = 'none';                                                   ms = 1600 },
    @{ n = 'press';     kind = 'tap';   x1 = $cx0; y1 = $cy;                             ms = 1600 },
    @{ n = 'drag_far';  kind = 'swipe'; x1 = $cx0; y1 = $cy; x2 = 300; y2 = $cy;         ms = 2200 },
    # the drag starts at the Core's CENTRE, which is where the previous finger stopped (the Core
    # follows the finger, so after drag_far its centre is x=300 -> its window is 212..388)
    @{ n = 'drag_edge'; kind = 'swipe'; x1 = 300;  y1 = $cy; x2 = 20;  y2 = $cy;         ms = 2600 },
    @{ n = 'settle';    kind = 'none';                                                   ms = 1600 }
)

function Say($m) {
    $line = (Get-Date -Format 'HH:mm:ss.fff') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m
    Add-Content -Path $log -Value $line
    Write-Host $m
}
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_touch_steps.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }
function Shot([string]$name) {
    $f = Join-Path $out ($name + '.png')
    Sh 'shell screencap -p /sdcard/touch.png' | Out-Null
    Sh "pull /sdcard/touch.png `"$f`"" | Out-Null
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
    # One file, three lines: show, THEN position (show() re-reads the persisted position), then size.
    Push "show`nat 452 1080`nsize 64"
    Start-Sleep -Milliseconds 900
    return $true
}
function EnsureLive {
    $now = [int](Sh "shell grep -c SERVICE_UNBIND $phone/$script:f")
    if ($now -ne $script:unbindBase) {
        Say '  !! binding died mid-pass - re-launching (the segment table restarts with it)'
        if (-not (Launch)) { return $false }
    }
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

if (-not $NoRecord) {
    Sh "shell rm -f $mp4remote" | Out-Null
    Say ('recording: screenrecord --time-limit ' + $limit + ' s')
    Start-Process -FilePath $adb -WindowStyle Hidden -ArgumentList @(
        'shell', "screenrecord --time-limit $limit --bit-rate 8000000 $mp4remote")
    Start-Sleep -Milliseconds 1200           # let the encoder start before the first gesture
}

$recStart = $sw.Elapsed.TotalSeconds
foreach ($s in $steps) {
    if (-not (EnsureLive)) { break }
    $t = [int](($sw.Elapsed.TotalSeconds - $recStart) * 1000)
    $desc = $s.kind
    if ($s.kind -eq 'tap') {
        # A HELD press, not `input tap`: that injects down and up 4 ms apart (measured 2026-09-27 -
        # `CORE_TOUCH down` 17:44:38.450, `up dragging=false` .454), which cuts core_press off after
        # 4 ms of its 400 ms, so the compression C3 asks about never renders. A same-point swipe holds
        # the finger down for its duration and the poller sees a real press.
        Sh "shell input swipe $($s.x1) $($s.y1) $($s.x1) $($s.y1) 300"
    } elseif ($s.kind -eq 'swipe') {
        Sh "shell input swipe $($s.x1) $($s.y1) $($s.x2) $($s.y2) 500"
        $desc = "swipe $($s.x1) $($s.y1) $($s.x2) $($s.y2)"
    }
    Say ('step ' + $s.n.PadRight(10) + ' t=' + $t + 'ms  <' + $desc + '>')
    $segments.Add($s.n + ',"' + $desc + '",' + $t + ',' + [int]($t + $s.ms) + ',' + [int]($t + 120))
    Start-Sleep -Milliseconds $s.ms
}
$segments | Set-Content -Path (Join-Path $out 'segments.csv') -Encoding ASCII
Shot 'core_touch_final'

if (-not $NoRecord) {
    $waitMs = [int](($recStart + $limit - $sw.Elapsed.TotalSeconds) * 1000)
    if ($waitMs -gt 0) { Say ('waiting ' + $waitMs + 'ms for the recorder to finalise'); Start-Sleep -Milliseconds $waitMs }
    $local = Join-Path $out 'core_touch.mp4'
    Sh "pull $mp4remote `"$local`"" | Out-Null
    if (Test-Path $local) {
        Say ('video ' + (Get-Item $local).Length + 'B -> extracting every frame at ' + $Fps + ' fps')
        & $ffmpeg -y -loglevel error -i $local -vf "fps=$Fps" -q:v 2 (Join-Path $out 'frames\f_%04d.png') 2>&1 |
            ForEach-Object { Say ('  ffmpeg ' + $_) }
        Say ('frames: ' + (Get-ChildItem (Join-Path $out 'frames\*.png')).Count)
    } else { Say 'FAIL: no video pulled' }
}

# ---------- the service's own account of the gestures ----------
# AND INTO A FILE, because this is the exact instrument for two of C3's claims (added by the M8 pass,
# 2026-09-28). `CORE_MOVED x=` is logged at the report of the drag's own poller tick and carries the
# window's final rect - so the first line is drag_far's answer and the second drag_edge's, which is
# both the magnet and its control. The pixel tracker in `core_state_audit.py touch` needs BOTH ring
# crossings on one row inside a window around the centre it last held, and on this pass it lost a Core
# that had travelled 237 px over the app's own teal UI ("the Core travelled 184 px (x 539 -> 357)" with
# the service's own line right there saying x=215): the drag read FAIL on the instrument, not the Core.
# The lines are written to `touch.log` next to segments.csv, which the analyzer reads for those claims.
$att = Sh "shell grep -c CORE_ATTACH $phone/$script:f"
$unb = Sh "shell grep -c SERVICE_UNBIND $phone/$script:f"
Say ('elapsed=' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's  CORE_ATTACH=' + $att + '  SERVICE_UNBIND=' + $unb)
$tlog = Join-Path $out 'touch.log'
@((Sh "shell grep -e CORE_TOUCH -e CORE_MOVED -e CORE_TAP $phone/$script:f") -split "`n") |
    Where-Object { $_.Trim() } | Set-Content -Path $tlog -Encoding ASCII
Get-Content $tlog | ForEach-Object { Say ('  log ' + $_) }
Say ('output in ' + $out + '   (segments.csv tells the analyzer which frame is which gesture,' +
     ' touch.log is the service''s own positions)')
Say 'DONE'
