# V3.2 Core Phase A - drive the Core through every visual state and screenshot each one.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_states.ps1
#   powershell ... -File tools\core_states.ps1 -Sizes        (adds the 48/56/64 dp mark comparison)
#
# The Core is driven through its debug channel (fetch-spike/core.cmd), exactly the pattern the
# chain test used. Phase A is visual only - nothing here detects, taps or downloads anything.
# Shots land in test_out\core_visual\ and are the evidence for cells K-A2/K-A3/K-A4.
param(
    [switch]$Sizes,
    [string]$States = 'idle,wake,detected,pressed,dragging,snapped,progress,paused,resuming,completing,complete,failed'
)

$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root 'test_out\core_visual'

function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }

# Writes one command file, then pushes it; the service's poller consumes and deletes it.
function Cmd([string]$body) {
    $tmp = [IO.Path]::GetTempFileName()
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push `"$tmp`" $phone/core.cmd" | Out-Null
    Remove-Item $tmp -Force
}

function Shot([string]$name) {
    Start-Sleep -Milliseconds 800
    Sh "shell screencap -p /sdcard/$name.png" | Out-Null
    Sh "pull /sdcard/$name.png `"$out\$name.png`"" | Out-Null
    Sh "shell rm /sdcard/$name.png" | Out-Null
    Write-Host "shot: $name"
}

if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb"; exit 1 }
if ((Sh 'devices') -notmatch "`tdevice") {
    Write-Host 'No device. Plug the phone into USB and run tools\wifi_adb.ps1 first.'
    exit 1
}
New-Item -ItemType Directory -Force -Path $out | Out-Null

Cmd 'show'
Start-Sleep -Seconds 2
Shot 'core_00_show_idle'

foreach ($s in $States.Split(',')) {
    $st = $s.Trim()
    if (-not $st) { continue }
    Cmd "state $st"
    Start-Sleep -Milliseconds 900
    Shot "core_state_$st"
}

Cmd "state progress`nprogress 0"
Shot 'core_progress_000'
foreach ($v in 25, 50, 75, 100) {
    Cmd "state progress`nprogress $v"
    Shot ("core_progress_{0:d3}" -f $v)
}

if ($Sizes) {
    foreach ($dp in 48, 56, 64) {
        Cmd "size $dp`nstate detected"
        Start-Sleep -Milliseconds 700
        Shot "core_size_${dp}dp"
    }
    Cmd 'size 64'
}

Cmd 'pause 0' ; Cmd 'idle'
Shot 'core_zz_idle_after'
Cmd 'hide'

# The service's own log lines for the run (CORE_*), newest 30.
$ls = Sh "shell ls -t $phone"
$newest = (($ls -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1)
if ($newest) {
    Write-Host ''
    Write-Host "--- CORE_ lines from $($newest.Trim()) ---"
    @((Sh "shell cat $phone/$($newest.Trim())") -split "`n") |
        Where-Object { $_ -match 'CORE_' } | Select-Object -Last 30
}
Write-Host ''
Write-Host "Screens in $out - review these against the design sheets (cells K-A2/K-A3/K-A4)."
