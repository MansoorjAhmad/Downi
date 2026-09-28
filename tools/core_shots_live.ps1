# V3.3 Core: on-device state capture for a ROM that unbinds the service by itself.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_shots_live.ps1
#
# WHY THIS EXISTS (2026-09-27, three wasted gate runs):
#  1. The vivo V2058 unbinds FetchSpikeService ~40 s after a bind, cleanly and unprompted
#     (SERVICE_UNBIND + FGS_STOP + SERVICE_DESTROY; HEARTBEAT uptime_s=30 at 10:55:28, dead at
#     10:55:38). The Core window dies with the binding, and the debug command channel lives in the
#     service - so a command pushed after that moment is simply never read. `tools\core_gate.ps1`
#     sleeps 8 s up front and spends 3 adb calls per shot, so it missed the window and wrote 28
#     screenshots of an empty app that look exactly like a rendering bug.
#  2. Arming by hand makes it worse: `settings delete` + `put secure enabled_accessibility_services`
#     looks successful (SERVICE_CONNECTED, CORE_READY, even CORE_ATTACH) and then the framework's
#     delayed reaction to the *delete* lands ~2 s later and kills the fresh bind. The process stays
#     alive, so `pidof` and `settings get` both report healthy. Do NOT touch the setting: this ROM
#     re-binds the service by itself when the app is launched.
#  3. `at x y` must come AFTER `show`: DowniCore.show() rebuilds its WindowManager.LayoutParams from
#     the persisted prefs, so a position pushed while hidden is silently discarded.
#
# So: launch, then drive with 2 adb calls per shot, checking liveness before every shot and
# re-launching (which re-binds) when the ROM has dropped us. Output uses the canonical `core_*`
# names that tools\core_review_sheets.ps1 and tools\core_state_audit.py already expect.
param(
    [int]$StepMs = 420,          # settle time between a command and its shot
    [int]$EndMs  = 900,          # extra settle for the `*_end` shot of a transient state
    [string]$Out = 'test_out\core_visual'
)
$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root $Out
$log   = Join-Path $root '_core_shots_live.log'
$sw    = [Diagnostics.Stopwatch]::StartNew()

function Say($m) { Add-Content -Path $log -Value ((Get-Date -Format 'HH:mm:ss') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m); Write-Host $m }
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_live.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }
function Shot([string]$name) {
    $f = Join-Path $out ($name + '.png')
    Sh 'shell screencap -p /sdcard/live.png' | Out-Null
    Sh "pull /sdcard/live.png `"$f`"" | Out-Null
    if (Test-Path $f) { Say ('  ' + $name + ' ' + (Get-Item $f).Length + 'B') } else { Say ('  ' + $name + ' FAILED') }
}

# ---------- bind (never by writing the setting) ----------
$script:f = ''
$script:unbindBase = 0
function Launch {
    Sh "shell am force-stop $pkg" | Out-Null
    Start-Sleep -Milliseconds 1200
    Sh "shell am start -n $pkg/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 2600
    $script:f = NewestLog
    $ready = Sh "shell grep -c CORE_READY $phone/$script:f"
    if ($ready -notmatch '^[1-9]') { Say 'FAIL: no CORE_READY after launch'; return $false }
    $script:unbindBase = [int](Sh "shell grep -c SERVICE_UNBIND $phone/$script:f")
    Say ('bound: ' + $script:f + ' (unbind baseline ' + $script:unbindBase + ')')
    # show first, THEN position (show() re-reads the persisted x/y and would discard an earlier
    # `at`), and BOTH in one push: two pushes 600 ms apart raced the poller (it consumed only the
    # second write, so the Core stayed hidden all pass and every state logged stage=null — the M2
    # run, 2026-09-27). One file, two lines: the service consumes every line it finds.
    Push "show`nat 452 1080"
    Start-Sleep -Milliseconds 800
    return $true
}
# Liveness gate before every shot: one grep. If the ROM dropped us, re-launch (which re-binds) and
# re-show the Core, so a pass can outlive as many 40 s windows as it needs.
function EnsureLive {
    $now = [int](Sh "shell grep -c SERVICE_UNBIND $phone/$script:f")
    if ($now -ne $script:unbindBase) { Say '  !! binding died mid-pass - re-launching'; if (-not (Launch)) { return $false } }
    return $true
}

New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')))
if (-not (Launch)) { Say 'DONE'; exit 1 }

# ---------- the pass (names match core_review_sheets.ps1) ----------
Shot 'core_00_show_idle'
foreach ($s in 'idle', 'detected', 'dragging', 'paused', 'complete') {
    if (-not (EnsureLive)) { break }
    Push "state $s"; Start-Sleep -Milliseconds $StepMs; Shot "core_state_$s"
}
foreach ($s in 'wake', 'pressed', 'snapped', 'resuming', 'completing', 'failed') {
    if (-not (EnsureLive)) { break }
    Push "state $s"; Start-Sleep -Milliseconds $StepMs
    Shot "core_state_${s}_mid"                      # catch the transition itself
    Start-Sleep -Milliseconds $EndMs
    Shot "core_state_${s}_end"                      # and its settled look
}
Push 'state progress'
foreach ($v in 0, 25, 50, 75, 100) {
    if (-not (EnsureLive)) { break }
    # `state progress` rides WITH the first value: separate pushes race the poller (it consumed
    # only the second write and the ring shots showed the previous look -- the M2 pass, 2026-09-27)
    Push $(if ($v -eq 0) { "state progress`nprogress 0" } else { "progress $v" })
    Start-Sleep -Milliseconds $StepMs
    Shot ('core_progress_' + $v.ToString('000'))
}
Push 'state idle'; Start-Sleep -Milliseconds $StepMs; Shot 'core_zz_idle_after'
Push 'hide'; Start-Sleep -Milliseconds 500; Shot 'core_zz_hidden'

# ---------- proof + the geometry the review sheet must crop to ----------
$unb = Sh "shell grep -c SERVICE_UNBIND $phone/$script:f"
$att = Sh "shell grep -c CORE_ATTACH $phone/$script:f"
$moves = Sh "shell grep -h CORE_MOVE $phone/$script:f"
Say ('elapsed=' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's  CORE_ATTACH=' + $att + '  SERVICE_UNBIND=' + $unb)
if ($moves) { Say ('  ' + (($moves -split "`n") | Select-Object -Last 2)) }
$prefs = Sh "shell run-as $pkg cat /data/data/$pkg/shared_prefs/downi_fetcher.xml"
$mx = [regex]::Match($prefs, 'name="core_x" value="(\d+)"')
$my = [regex]::Match($prefs, 'name="core_y" value="(\d+)"')
$ms = [regex]::Match($prefs, 'name="core_size_dp" value="(\d+)"')
if ($mx.Success) {
    $at = $mx.Groups[1].Value + ',' + $my.Groups[1].Value
    $dp = if ($ms.Success) { $ms.Groups[1].Value } else { '64' }
    Say ('device prefs say the Core is at ' + $at + ' (' + $dp + ' dp) - crop with: -At ' + $at + ' -SizeDp ' + $dp)
} else { Say 'could not read core_x/core_y prefs - determine the crop box from the shots' }
Say ('shots in ' + $out)
Say 'DONE'
