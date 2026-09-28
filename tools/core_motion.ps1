# V3.3 Core 2.0 - M4's C5 motion contract on the phone: freeze / resume / completion.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_motion.ps1
#   powershell ... -File tools\core_motion.ps1 -NoRecord      (stills only, no video/frames)
#
# WHY VIDEO, AND WHAT IT IS FOR. The C5 contract is about TIME, and its three claims each need a
# different instrument:
#
#   * PAUSED freezes the ring with no motion  ->  two `screencap`s held 2.6 s apart must be
#     pixel-identical (stills give that for free), AND the video must show no change in the hold.
#   * RESUME picks it up where it stopped     ->  the arc's painted span at RESUMING must equal the
#     span it had at PAUSED (62%), not 0%. A still at each step is enough.
#   * COMPLETING holds the full circle first  ->  this one needs every frame. `core_complete`'s ring
#     trim runs 86 -> 100 over frames 0..18 (300 ms) while the merge flash peaks at frame 14 (233 ms),
#     the ring fades from frame 20 (333 ms) and CoreHost's own arc collapses 1 -> 0.35 over
#     COMPLETE_MS = 400 ms: the whole sequence is under 0.8 s. `screencap` costs ~0.7 s per shot and
#     the command poller runs every 800 ms, so stills cannot resolve it at all.
#
# So: screenrecord the pass, log the wall-clock instant of every command push, extract every frame at
# -Fps with the bundled ffmpeg, and let `tools\core_state_audit.py frames` measure those frames against
# the segment table this script writes (`segments.csv`). The stills stay as a second, independent
# instrument for the two claims they can actually resolve.
#
# Two rig lessons from the M2 pass are kept: `at` must come AFTER `show` (show() re-reads the
# persisted position), and the commands of one step ride in ONE pushed file (the poller consumes the
# whole file; two pushes 600 ms apart race it). The ROM also unbinds the service by itself ~40 s after
# a bind, so liveness is checked before every step and the pass re-launches if it has to.
param(
    [string]$Out  = 'test_out\core_motion',
    [int]$HoldMs  = 3200,      # the PAUSED hold: the freeze window, in ms
    [int]$Fps     = 30,        # frames extracted per second for the analyzer
    [switch]$NoRecord
)
$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$ffmpeg = Join-Path $root 'tools\ffmpeg.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root $Out
$log   = Join-Path $root '_core_motion.log'
$mp4remote = '/sdcard/core_motion.mp4'
$sw    = [Diagnostics.Stopwatch]::StartNew()

# name, the command file's body, how long to hold before the next step. Every hold must outlast the
# poller's worst latency: commands are served 0.4-1.7 s after the push (measured), and a step shorter
# than its own latency has its state change land inside the NEXT step's window - which is what made
# the first pass's C5 verdicts compare each state against the previous state's frames. The analyzer
# now finds each change itself (see `do_frames`), but it can only find it inside the step's window.
$steps = @(
    @{ n = 'detected';    cmd = 'state detected';                 ms = 2600 },   # the motion CONTROL
    # The two ring-bearing steps are held 4.4 s, not 2.6 s. The analyzer measures each step from the
    # frame its change actually appears in, and that frame has to be INSIDE the step's window - which
    # ends where the next command is pushed. Measured on 2026-09-27: the poller served these pushes
    # 1.0-1.6 s later, so a 2.6 s hold left the ring's own appearance (`progress 62`, the frame the
    # whole freeze/resume comparison hangs on) 138 ms past the window's end and the step read as the
    # previous look. With 4.4 s the change lands with ~2 s of settled frames behind it.
    @{ n = 'progress62';  cmd = "state progress`nprogress 62";    ms = 4400 },
    @{ n = 'paused';      cmd = 'state paused';                   ms = $HoldMs },
    @{ n = 'resuming';    cmd = 'state resuming';                 ms = 2600 },
    @{ n = 'progress62b'; cmd = "state progress`nprogress 62";    ms = 4400 },
    @{ n = 'completing';  cmd = "state completing`nprogress 100"; ms = 2600 },
    @{ n = 'complete';    cmd = 'state complete';                 ms = 2600 },
    @{ n = 'idle';        cmd = 'state idle';                     ms = 2200 },
    @{ n = 'hide';        cmd = 'hide';                           ms = 1000 }
)

function Say($m) {
    $line = (Get-Date -Format 'HH:mm:ss.fff') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m
    Add-Content -Path $log -Value $line
    Write-Host $m
}
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_motion.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }
function Shot([string]$name) {
    $f = Join-Path $out ($name + '.png')
    Sh 'shell screencap -p /sdcard/motion.png' | Out-Null
    Sh "pull /sdcard/motion.png `"$f`"" | Out-Null
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
    Push "show`nat 452 1080`nsize 64"      # one file, three lines: show, THEN position, then size
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
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')) + '  hold=' + $HoldMs + 'ms  fps=' + $Fps)
if (-not (Test-Path $adb)) { Say 'adb not found'; exit 1 }
if (-not (Launch)) { Say 'DONE'; exit 1 }

$totalMs = ($steps | ForEach-Object { $_.ms } | Measure-Object -Sum).Sum
$limit = [int]([Math]::Ceiling($totalMs / 1000.0) + 3)     # the recorder finalises on its time limit
$segments = New-Object System.Collections.Generic.List[string]
$segments.Add('name,cmd,start_ms,end_ms,effect_ms')

if (-not $NoRecord) {
    Sh "shell rm -f $mp4remote" | Out-Null
    Say ('recording: screenrecord --time-limit ' + $limit + ' s')
    Start-Process -FilePath $adb -WindowStyle Hidden -ArgumentList @(
        'shell', "screenrecord --time-limit $limit --bit-rate 8000000 $mp4remote")
    Start-Sleep -Milliseconds 1200                          # let the encoder start before step 1
}

$recStart = $sw.Elapsed.TotalSeconds
foreach ($s in $steps) {
    if (-not (EnsureLive)) { break }
    $t = [int](($sw.Elapsed.TotalSeconds - $recStart) * 1000)
    Push $s.cmd
    Say ('step ' + $s.n.PadRight(12) + ' t=' + $t + 'ms  <' + ($s.cmd -replace "`n", ' | ') + '>')
    # The poller runs every 800 ms and the command lands 0.4-1.7 s after the push (measured), so
    # `effect_ms` brackets only the EARLIEST instant it could have taken effect; the analyzer finds
    # the change itself in the frames. It is kept because a step that nothing changed in should be
    # visible as such.
    $rec = $s.cmd -replace "`n", '; '
    $segments.Add($s.n + ',"' + $rec + '",' + $t + ',' + [int]($t + $s.ms) + ',' + [int]($t + 450))
    if ($s.n -eq 'paused') {
        # both stills must land INSIDE the hold, i.e. after the worst-case latency - a still taken
        # before the pause is served photographs the previous look and reads as "it moved"
        Start-Sleep -Milliseconds 1800
        Shot 'core_motion_paused_a'
        Start-Sleep -Milliseconds 1400
        Shot 'core_motion_paused_b'
        Start-Sleep -Milliseconds ([Math]::Max(0, $s.ms - 3200))
    } else {
        Start-Sleep -Milliseconds $s.ms
    }
}
$segments | Set-Content -Path (Join-Path $out 'segments.csv') -Encoding ASCII

if (-not $NoRecord) {
    $waitMs = [int](($recStart + $limit - $sw.Elapsed.TotalSeconds) * 1000)
    if ($waitMs -gt 0) { Say ('waiting ' + $waitMs + 'ms for the recorder to finalise'); Start-Sleep -Milliseconds $waitMs }
    $local = Join-Path $out 'core_motion.mp4'
    Sh "pull $mp4remote `"$local`"" | Out-Null
    if (Test-Path $local) {
        Say ('video ' + (Get-Item $local).Length + 'B -> extracting every frame at ' + $Fps + ' fps')
        & $ffmpeg -y -loglevel error -i $local -vf "fps=$Fps" -q:v 2 (Join-Path $out 'frames\f_%04d.png') 2>&1 |
            ForEach-Object { Say ('  ffmpeg ' + $_) }
        Say ('frames: ' + (Get-ChildItem (Join-Path $out 'frames\*.png')).Count)
        # the frame count vs the duration is the proof the pass really was a movie, not one still
        & (Join-Path $root 'tools\ffprobe.exe') -v error -select_streams v:0 -count_frames `
            -show_entries stream=nb_read_frames,duration,avg_frame_rate -of default=nw=1 $local 2>&1 |
            ForEach-Object { Say ('  probe ' + $_) }
    } else { Say 'FAIL: no video pulled' }
}

# ---------- the service's own account of the pass ----------
$att = Sh "shell grep -c CORE_ATTACH $phone/$script:f"
$unb = Sh "shell grep -c SERVICE_UNBIND $phone/$script:f"
Say ('elapsed=' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's  CORE_ATTACH=' + $att + '  SERVICE_UNBIND=' + $unb)
@((Sh "shell grep CORE_STATE $phone/$script:f") -split "`n") | Select-Object -Last 12 |
    ForEach-Object { Say ('  log ' + $_) }
Say ('output in ' + $out + '   (segments.csv tells the analyzer which frame is which step)')
Say 'DONE'
