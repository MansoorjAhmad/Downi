# core_reach.ps1 - W2's gate: capture the Reach (sheet C4) on a REAL session.
#
# WHY. The Reach only fires for a tap that has something to resolve: tools\core_touch.ps1's tap runs
# over whatever is in front and logs CORE_TAP_NO_SESSION. C4's tether/node/capture have therefore only
# ever been proven by the service's own log (DEVICE_TEST.md 0g-2: REACH_BEGIN core=88,1261 ->
# CHAIN_SCAN -> CHAIN_TARGET_CLICK), never by a pixel. This puts Instagram on a Reel, tapes the screen
# while it taps the Core, and leaves both the video and the service log behind for the analyzer.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\core_reach.ps1
#
param(
    [string]$Out  = 'test_out\core_reach',
    [string]$App  = 'com.zhiliaoapp.musically',   # TikTok: the surface whose a11y tree carries ids
    [string]$Url  = '',                           # '' = launch the app and let its feed play
    [int]$Fps     = 30
)
$ErrorActionPreference = 'SilentlyContinue'
$root   = Split-Path -Parent $PSScriptRoot
$adb    = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$ffmpeg = Join-Path $root 'tools\ffmpeg.exe'
$pkg    = 'com.omnidownloader.app'
$phone  = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out    = Join-Path $root $Out
$log    = Join-Path $root '_core_reach.log'
$mp4    = '/sdcard/reach.mp4'
$cx     = 452 + 88                     # the Core's centre for `at 452 1080` + `size 64` (176 px)
$cy     = 1080 + 88
$sw     = [Diagnostics.Stopwatch]::StartNew()

function Say($m) {
    $line = (Get-Date -Format 'HH:mm:ss.fff') + ' +' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's ' + $m
    Add-Content -Path $log -Value $line
    Write-Host $m
}
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }
function Push([string]$body) {
    $tmp = Join-Path $env:TEMP 'core_cmd_reach.txt'
    Set-Content -Path $tmp -Value $body -Encoding ASCII
    Sh "push $tmp $phone/core.cmd" | Out-Null
}
function NewestLog { (((Sh "shell ls -t $phone") -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1) }

New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'frames') | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $out 'frames\*.png') -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')))

# 1) the service, bound by launching the app - never by writing the a11y setting (see core_touch.ps1)
Sh "shell am force-stop $pkg" | Out-Null
Start-Sleep -Milliseconds 1200
Sh "shell am start -n $pkg/.MainActivity" | Out-Null
Start-Sleep -Milliseconds 2600
$f = NewestLog
if ((Sh "shell grep -c CORE_READY $phone/$f") -notmatch '^[1-9]') { Say 'FAIL: no CORE_READY after launch'; Say 'DONE'; exit 1 }
Say ('bound: ' + $f)

# 2) the Core on screen at the pass's known spot (the state sweep leaves it hidden)
Push "show`nat 452 1080`nsize 64"
Start-Sleep -Milliseconds 900

# 3) the platform app, on a video. TikTok by default: Instagram's Reels view reports
#    `confidence=NONE note=no_url_or_id_in_tree` on this device (measured 2026-09-28), so nothing
#    there can open a session at all and the Core honestly refuses the tap.
if ($Url -ne '') {
    Sh "shell am start -a android.intent.action.VIEW -d $Url $App" | Out-Null
} else {
    Sh "shell monkey -p $App -c android.intent.category.LAUNCHER 1" | Out-Null
}
Start-Sleep -Seconds 10
$sess = Sh "shell grep -c SESSION_START $phone/$f"
$det  = Sh "shell grep -c 'CORE_STATE detected' $phone/$f"
$cand = Sh "shell grep -cE 'STEP3_DUMP.*confidence=(HIGH|MEDIUM|LOW)' $phone/$f"
Say ("platform up: SESSION_START=$sess  CORE_STATE detected=$det  candidates=$cand")

# 4) tape it while the Core is tapped ONCE
$rec = Start-Process -FilePath $adb -ArgumentList 'shell',"screenrecord --time-limit 7 --bit-rate 8000000 $mp4" -NoNewWindow -PassThru
Start-Sleep -Seconds 2
Say ("tap at $cx,$cy")
Sh "shell input tap $cx $cy" | Out-Null
Start-Sleep -Seconds 6
Sh "pull $mp4 `"$out\reach.mp4`"" | Out-Null
Say ('video ' + (Get-Item "$out\reach.mp4").Length + 'B')

# 5) the service's own account of what that tap did
foreach ($p in 'CORE_TAP ', 'REACH_BEGIN', 'REACH_TO', 'REACH_CAPTURE', 'CHAIN_', 'RUN_END', 'CORE_TAP_NO_SESSION') {
    $hit = Sh "shell grep -E '$p' $phone/$f"
    if ($hit) { foreach ($line in ($hit -split "`n")) { Say ('  log ' + $line.Trim()) } }
}
& $ffmpeg -y -loglevel error -i "$out\reach.mp4" -vf "fps=$Fps" -q:v 2 (Join-Path $out 'frames\f_%04d.png') 2>&1 |
    ForEach-Object { Say ('  ffmpeg ' + $_) }
Say ('frames: ' + (Get-ChildItem (Join-Path $out 'frames') -Filter *.png).Count)
Say 'DONE'
