# V3.3 Core 2.0 - M4's C5 motion contract, read from the service's own account of the stage.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_motion_probe.ps1
#
# WHY THIS EXISTS NEXT TO core_motion.ps1. That script records the pass and measures PIXELS, which is
# what the three C5 claims are phrased in ("frozen", "picks up", "holds the full circle") - but pixels
# cannot tell two faults apart that look identical from outside: "the composition never animated" and
# "the composition animated and its frames never reached the screen" (the screenshots of the M4 pass
# of 2026-09-27 are consistent with both). The `stage` debug command answers them directly: it logs
# the file the stage is holding, its live frame, whether its animator is still running, and how many
# times it has actually been drawn, timestamped by the phone at the instant it is served.
#
# It also removes this pass's timing problem for good. The old rig stamped each step `push + 450 ms`
# as the moment it took effect and the poller is not that punctual, so the analyzer compared steps
# against the wrong frames; worse, a push that the poller never consumed (a `show` that went missing
# on the first run of this script) made every state log `stage=null` - an app fault that was really a
# rig fault. Every question here is its own command, and every push is verified SERVED before the
# next one goes out, so the phone's own timestamps ARE the timeline.
#
# Pass: DETECTED (the looping control) -> progress 62% -> PAUSED (held) -> RESUMING -> progress 62%
# again -> COMPLETING (whose own promotion to COMPLETE is what plays the merge) -> the merge -> IDLE.
# Each step probes twice: in the same poller tick as its state change (so the file is caught at frame
# 0, running) and after it has had time to play out (so it is caught settled). All of it lands in
# `_core_motion_probe.log` and in `probe.log`, next to the shots.
param(
    [string]$Out  = 'test_out\core_motion_probe',
    [int]$HoldMs  = 2600        # the PAUSED hold: the two probes sit at each end of it
)
$ErrorActionPreference = 'SilentlyContinue'
$root   = Split-Path -Parent $PSScriptRoot
$adb    = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg    = 'com.omnidownloader.app'
$phone  = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out    = Join-Path $root $Out
$log    = Join-Path $root '_core_motion_probe.log'
$sw     = [Diagnostics.Stopwatch]::StartNew()

# name       what the step pushes (one file per step - the poller consumes a whole file)   the probes
#            probe = $true appends a `stage` line to that same file: the note is then taken in the
#            very tick the state changed, i.e. the file at frame 0. `after` are the later probes.
$steps = @(
    @{ n = 'detected';    cmd = 'state detected';                 probe = $true;  after = @(1300, 1600) },
    @{ n = 'progress62';  cmd = "state progress`nprogress 62";    probe = $true;  after = @(1400) },
    @{ n = 'paused';      cmd = 'state paused';                   probe = $true;  after = @(1400, $HoldMs) },
    @{ n = 'resuming';    cmd = 'state resuming';                 probe = $true;  after = @(1400) },
    @{ n = 'progress62b'; cmd = "state progress`nprogress 62";    probe = $true;  after = @(1400) },
    @{ n = 'completing';  cmd = "state completing`nprogress 100"; probe = $true;  after = @(1200) },
    @{ n = 'merge';       cmd = $null;                            probe = $true;  after = @(1600) },
    @{ n = 'idle';        cmd = 'state idle';                     probe = $true;  after = @(1200) }
)

function Say($m) {
    $line = (Get-Date -Format 'HH:mm:ss.fff') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m
    Add-Content -Path $log -Value $line
    Write-Host $m
}
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_probe.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function CmdCount([string]$first) { [int](Sh "shell grep -c 'CORE_CMD cmd=$first' $phone/$script:f") }
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }

# A push is only a request: the poller may consume the file, or miss it because the next one landed
# first (the M4 probe run of 2026-09-27 lost its `show` this way, and every state then logged
# `stage=null` - which reads exactly like an app that never loads its art). Count the command before
# and after and wait for the count to grow, so a lost step is loud and retried instead of silent.
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
    Sh 'shell screencap -p /sdcard/probe.png' | Out-Null
    Sh "pull /sdcard/probe.png `"$f`"" | Out-Null
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
    # is driven: a Core that was never shown has no geometry, so every state would log `stage=null`
    # (there is no size to place the composition in), which is indistinguishable from broken art.
    if (-not (PushServed "show`nat 452 1080`nsize 64" 'show')) { Say 'FAIL: show never served'; return $false }
    Start-Sleep -Milliseconds 1500      # let it lay out: onSizeChanged is what gives the stage a size
    return $true
}
function EnsureLive {
    $now = [int](Sh "shell grep -c SERVICE_UNBIND $phone/$script:f")
    if ($now -ne $script:unbindBase) {
        Say '  !! binding died mid-pass - re-launching (the state timeline restarts with it)'
        if (-not (Launch)) { return $false }
    }
    return $true
}

New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')) + '  hold=' + $HoldMs + 'ms  probe=stage')
if (-not (Test-Path $adb)) { Say 'adb not found'; exit 1 }
if (-not (Launch)) { Say 'DONE'; exit 1 }

foreach ($s in $steps) {
    if (-not (EnsureLive)) { break }
    if ($s.cmd) {
        $body = $s.cmd
        if ($s.probe) { $body = $body + "`nstage" }   # the same-tick probe: the file at frame 0
        Say ('step ' + $s.n.PadRight(12) + '  <' + ($body -replace "`n", ' | ') + '>')
        PushServed $body (($s.cmd -split "`n")[0]) | Out-Null
    } else {
        Say ('step ' + $s.n.PadRight(12) + '  (nothing pushed: the promotion to COMPLETE happens by itself)')
        Probe          # pushed within a poller tick of the state change above, so it is served inside
                       # the merge's own 750 ms - the only probe that can catch core_complete running
    }
    foreach ($w in $s.after) {
        Start-Sleep -Milliseconds ([int]$w)
        Probe
    }
    if ($s.n -eq 'paused') { Shot 'probe_paused' }
}

# ---------- the service's own account of the pass ----------
Say ''
Say 'phone log (CORE_CMD / CORE_STATE / CORE_STAGE / CORE_PROGRESS, in the phone''s own order):'
# NB: no `|` in a grep that travels through `cmd /c` - cmd does not honour single quotes, so it
# splits the pattern into a PIPE and the grep returns nothing at all (the M4 probe run of
# 2026-09-27 came back with an empty evidence table for exactly this reason). Multiple -e are safe.
$lines = @((Sh "shell grep -e CORE_CMD -e CORE_STATE -e CORE_STAGE -e CORE_PROGRESS $phone/$script:f") -split "`n") |
    Where-Object { $_.Trim() }
$dump = Join-Path $out 'probe.log'
$lines | Set-Content -Path $dump -Encoding ASCII
$lines | ForEach-Object { Say ('  ' + $_) }

# the readings, one line per probe: which file, which frame, is its animator still running, how many
# frames it has actually been drawn. This IS the evidence - the raw lines above are the audit trail.
Say ''
Say 'stage readings (file / frame / running / draws):'
foreach ($l in $lines) {
    if ($l -notmatch 'CORE_STAGE ') { continue }
    $t = ($l -split ' ')[0]
    $note = $l.Substring($l.IndexOf('CORE_STAGE ') + 11)
    $file = if ($note -match 'stage=(\S+)') { $matches[1] } else { '?' }
    $fr = if ($note -match '\bf=(\d+)') { $matches[1] } else { '-' }
    $run = if ($note -match 'run=(\w+)') { $matches[1] } else { '-' }
    $dr = if ($note -match 'draws=(\d+)') { $matches[1] } else { '-' }
    Say ('  ' + $t + '  ' + $file.PadRight(16) + ' f=' + $fr.PadRight(4) + ' run=' + $run.PadRight(6) + ' draws=' + $dr)
}
if (-not @($lines | Where-Object { $_ -match 'CORE_STAGE stage=core_idle_ready' })) {
    Say ''
    Say 'FAIL: DETECTED never loaded core_idle_ready - the view had no geometry or the push was lost'
    Say '      (check the "show never served" line above before reading anything else in this pass)'
}
$unb = Sh "shell grep -c SERVICE_UNBIND $phone/$script:f"
Say ''
Say ('elapsed=' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's  SERVICE_UNBIND=' + $unb)
Say ('output in ' + $out + '   (probe.log is the evidence; the shots are the eye half)')
Say 'DONE'
