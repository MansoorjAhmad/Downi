# V3.2 Core Phase A - cell K-A5: what the Core costs while idle, *measured* instead of asserted.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_idle_cost.ps1
#   powershell ... -File tools\core_idle_cost.ps1 -Minutes 10      (the soak the plan asks for)
#
# The Core's contract is a static idle: no invalidation loop, no wake lock, no battery drain while
# nothing changes (sheet 5: "subtle and calm"). So this draws the Core in a named state, resets
# gfxinfo, and then samples every `-SampleSeconds`:
#
#   frames   `dumpsys gfxinfo <pkg>` "Total frames rendered"  -> must not grow while the state is still
#   cpu      `ps -o TIME` for the process                     -> must not climb
#   locks    our package inside `dumpsys power`'s wake locks  -> must be absent
#   draws    the Core's OWN draw counter (the `stage` debug note) -> must not grow either
#
# WHY `draws`, AND WHY `-Offscreen` (added by the M8 pass, 2026-09-28). `gfxinfo` counts frames for the
# whole PROCESS, and the package has two windows: the Core overlay and MainActivity. Measured on the
# vivo V2058 at the M8 gates: with MainActivity in the foreground the process draws ~61 fps and burns
# ~25 s of CPU per 20 s of wall clock from the app's own UI alone, while the Core's own counter sits
# still - so the frames column alone cannot answer "does the CORE stop drawing", and a foreground
# sample reads as a failure no matter how correct the Core is. Run with `-Offscreen` (the activity is
# sent to the back with KEYCODE_HOME and the Core overlay stays shown) and the frames column means the
# Core; `draws` is the Core's own answer in either case.
#
# Every sample is appended to _core_idle_cost.log as it happens, and the verdict is printed at the end.
param(
    [int]$Minutes = 3,
    [int]$SampleSeconds = 60,
    [string]$State = 'idle',
    [switch]$Offscreen
)

$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$log   = Join-Path $root '_core_idle_cost.log'
$tmp   = Join-Path $env:TEMP 'core_idle_cmd.txt'
$tmpStage = Join-Path $env:TEMP 'core_idle_stage.txt'

function Say($m) {
    Add-Content -Path $log -Value ((Get-Date -Format 'HH:mm:ss') + ' ' + $m)
    Write-Host $m
}

if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb"; exit 1 }
Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((& $adb devices) -join ' ') -replace "`r?`n", ' '))
if (-not (((& $adb devices) -join ' ') -match "`tdevice")) {
    Say 'NO DEVICE - plug the phone in (or run tools\wifi_adb.ps1) and try again'
    exit 1
}

function Frames {
    $out = (& $adb shell dumpsys gfxinfo $pkg) | Select-String -Pattern 'Total frames rendered'
    if (-not $out) { return -1 }
    return [int]($out[0].Line -replace '[^0-9]', '')
}

function CpuTime {
    $out = (& $adb shell ps -A -o NAME,TIME) | Select-String -Pattern $pkg
    if (-not $out) { return 'gone' }
    return ($out[0].Line.Trim() -split '\s+')[1]
}

function OurWakeLocks {
    ((& $adb shell dumpsys power) 2>&1) | Select-String -Pattern $pkg | Measure-Object | Select-Object -ExpandProperty Count
}

# The Core's own draw count, read from the same `stage` note the M4/M8 gates read: the file it holds,
# its live frame, whether its animator runs, whether the ambient budget is still armed, how many times
# the composition has actually been drawn. One push per sample; the poller costs ~1 s to serve it.
function NewestLog {
    ((((& $adb shell "ls -t $phone") 2>&1) -split "`r?`n") |
        Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1).Trim()
}
function CoreStage {
    Set-Content -Path $tmpStage -Value 'stage' -Encoding ASCII
    & $adb push $tmpStage "$phone/core.cmd" | Out-Null
    Start-Sleep -Milliseconds 1500
    $f = NewestLog
    if (-not $f) { return 'draws=?' }
    $line = ((& $adb shell "grep CORE_STAGE $phone/$f") 2>&1 | Select-Object -Last 1)
    if (-not $line) { return 'draws=?' }
    $d = if ($line -match 'draws=(\d+)') { $matches[1] } else { '?' }
    $r = if ($line -match 'run=(\w+)')   { $matches[1] } else { '?' }
    $a = if ($line -match 'amb=(\d)')    { $matches[1] } else { '-' }
    return ('draws=' + $d + ' run=' + $r + ' amb=' + $a)
}
function CoreDraws([string]$s) { if ($s -match 'draws=(\d+)') { [int]$matches[1] } else { -1 } }

# show the Core in the requested state, then let the frame counter be reset. With -Home the activity is
# put away first: the Core's overlay lives in its own window and stays shown (the M8 probe reads
# `vis=true` after HOME), so what is left drawing in this process is the Core.
Set-Content -Path $tmp -Value "show`nstate $State" -Encoding ASCII
& $adb push $tmp "$phone/core.cmd" | Out-Null
Start-Sleep -Seconds 2
if ($Offscreen) {
    & $adb shell input keyevent HOME | Out-Null
    Start-Sleep -Seconds 2
    Say 'KEYCODE_HOME: the activity is away, the Core overlay stays shown'
}
& $adb shell dumpsys gfxinfo $pkg reset | Out-Null
Start-Sleep -Seconds 1

$f0 = Frames
$c0 = CpuTime
$s0 = CoreStage
$d0 = CoreDraws $s0
Say ("baseline  frames=$f0  cpu=$c0  state=$State  core=$s0  (gfxinfo just reset)")

$worst = 0
$worstDraws = 0
for ($i = 1; $i -le $Minutes; $i++) {
    Start-Sleep -Seconds $SampleSeconds
    $f = Frames
    $c = CpuTime
    $locks = OurWakeLocks
    $s = CoreStage
    $delta = $f - $f0
    $dDelta = (CoreDraws $s) - $d0
    if ($delta -gt $worst) { $worst = $delta }
    if ($dDelta -gt $worstDraws) { $worstDraws = $dDelta }
    Say ("t+{0,3}s   frames={1} (+{2})  cpu={3}  ourWakeLocks={4}  core={5} (+{6} draws)" -f `
        ($i * $SampleSeconds), $f, $delta, $c, $locks, $s, $dDelta)
}

Say ''
Say ("VERDICT  frames drawn while idle: +" + $worst + "  (0 = nothing in the process repaints)")
Say ("         the Core's own draws: +" + $worstDraws + "  (0 = the composition never repainted)")
Say ("         baseline cpu=" + $c0 + " -> " + (CpuTime) + "  (compare by eye: a climbing TIME means work)")
Say ("         wake locks mentioning $pkg : " + (OurWakeLocks) + "  (0 = we hold none)")
if (-not $Offscreen) {
    Say '         NB: the frames column counts the WHOLE process, MainActivity included. Measured'
    Say '             2026-09-28: the foreground activity alone draws ~61 fps / ~1.25 cores of CPU, so'
    Say '             a climbing line here is not the Core unless the "core" column climbs with it.'
    Say '             Re-run with -Offscreen (or read "core") to answer the Core''s own question.'
}
Say ('K-A5 is owner-judged: this is the evidence, not the verdict.')
