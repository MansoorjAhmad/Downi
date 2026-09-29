# _L_probe3.ps1 - is "pause the video" a real, verifiable state, and does a PLAYING video really
# break `uiautomator dump`?
#
# WHY THIS EXISTS. The previous probe showed a dump succeeding on a feed that *looked* paused, but
# the screenshot carried no centre play-glyph and TikTok's own node still read `content-desc="Video"`
# - so "paused" was an assumption, not an observation. A rig that pauses before dumping is only
# honest if it can SEE the pause. Two independent signals are used here because neither alone is
# trustworthy on this ROM:
#   * `dumpsys media_session` - the player's own reported state (state=3 PLAYING / state=2 PAUSED);
#   * centre-region pixels - a 44x30 sample grid over the video area, hashed three times ~0.9 s
#     apart. Moving video = hashes differ; a frozen video = one hash. The status bar is excluded by
#     sampling only the middle of the screen (the ROM's "0.31 KB/s" readout changes every second and
#     would otherwise make a paused screen look alive).
#   * every dump runs under device-side `timeout 20`, so the documented idle-wait hang is bounded
#     and reported as a timed-out dump instead of eating the whole run.
$ErrorActionPreference = 'Continue'
$repo  = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$adb   = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$cline = 'C:\Users\Manso\AppData\Local\Cline'
$dir   = Join-Path $cline '_L'
$log   = Join-Path $dir 'probe3.txt'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Remove-Item $log -ErrorAction SilentlyContinue

function Say($m) { ((Get-Date).ToString('HH:mm:ss') + '  ' + $m) | Add-Content -Path $log }
function Sh($c)  { (cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul')) -join "`n" }
function Dev($c) { cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul') | Out-Null }

$tt    = 'com.zhiliaoapp.musically'
$ttAct = 'com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity'
Add-Type -AssemblyName System.Drawing

function Cap([string]$name) {
    $png = Join-Path $dir $name
    Remove-Item $png -ErrorAction SilentlyContinue
    cmd /c ('"' + $adb + '" exec-out screencap -p > "' + $png + '"') | Out-Null
    return $png
}

# Hash the middle of the screen only. Every 20th pixel over x 100..980, y 700..1300 - coarse
# enough to be cheap, dense enough that any real motion in a playing video changes the hash.
function CenterHash([string]$png) {
    if (-not (Test-Path $png)) { return 'NOCAP' }
    try { $bmp = [System.Drawing.Bitmap]::FromFile($png) } catch { return 'BADPNG' }
    $sb = New-Object System.Text.StringBuilder
    for ($y = 700; $y -lt 1300; $y += 20) {
        for ($x = 100; $x -lt 980; $x += 20) {
            $p = $bmp.GetPixel($x, $y)
            [void]$sb.Append($p.R); [void]$sb.Append(','); [void]$sb.Append($p.G); [void]$sb.Append(','); [void]$sb.Append($p.B); [void]$sb.Append(';')
        }
    }
    $bmp.Dispose()
    $md5 = [System.Security.Cryptography.MD5]::Create()
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($sb.ToString())
    return ([BitConverter]::ToString($md5.ComputeHash($bytes)) -replace '-', '').Substring(0, 12)
}

function DumpTimed($tag) {
    $dev = '/sdcard/_L_p3_' + $tag + '.xml'
    Dev ('rm -f ' + $dev)
    $t0 = Get-Date
    $o  = Sh ('timeout 20 uiautomator dump ' + $dev)
    $el = [int]((Get-Date) - $t0).TotalSeconds
    $ok = ($o -match 'dumped to')
    $sz = (Sh ('stat -c %s ' + $dev)).Trim()
    Say ('  dump[' + $tag + '] ' + $el + 's ok=' + $ok + ' size=' + $sz + " msg='" + ($o.Trim() -replace "`n", ' ') + "'")
    $local = Join-Path $dir ('p3_' + $tag + '.xml')
    Remove-Item $local -ErrorAction SilentlyContinue
    cmd /c ('"' + $adb + '" pull ' + $dev + ' "' + $local + '" 2>nul') | Out-Null
}

function FeedLabel($tag) {
    $p = Join-Path $dir ('p3_' + $tag + '.xml')
    if (-not (Test-Path $p)) { return 'no local xml' }
    try { $x = [xml](Get-Content $p) } catch { return 'unparseable xml' }
    $t = ''; $d = ''
    foreach ($n in $x.SelectNodes('//*[@bounds]')) {
        $id = ($n.'resource-id' -replace 'com.zhiliaoapp.musically:id/', '')
        if ($id -eq 'title' -and -not $t) { $t = ($n.text -replace '\s+', ' ').Trim() }
        if ($id -eq 'desc'  -and -not $d) { $d = ($n.text -replace '\s+', ' ').Trim() }
    }
    return ('title=' + $t + ' | desc=' + $d)
}

# One phase = three screencaps for the motion hash, the player's own reported state, then a timed dump.
function Probe($phase) {
    Say ('=== ' + $phase + ' ===')
    $hs = @()
    for ($k = 1; $k -le 3; $k++) {
        $hs += (CenterHash (Cap ('p3_' + $phase + '_' + $k + '.png')))
        if ($k -lt 3) { Start-Sleep -Milliseconds 900 }
    }
    $distinct = ($hs | Sort-Object -Unique).Count
    Say ('  pixels: ' + ($hs -join ' ') + '  distinct=' + $distinct + ' -> ' + $(if ($distinct -gt 1) { 'MOVING' } else { 'STILL' }))
    $st = (Sh 'dumpsys media_session') -split "`n" | Where-Object { $_ -match 'package=|state=' } | Select-Object -First 10
    if (-not $st) { Say '  media_session: no state lines at all (this ROM may not publish them)' }
    foreach ($l in $st) {
        $s = $l.Trim()
        if ($s -match 'musically|state=|[Aa]ctive') { Say ('  ms: ' + $s) }
    }
    DumpTimed $phase
    Say ('  feed: ' + (FeedLabel $phase))
}

Say ('toybox timeout check: ' + (Sh 'timeout 1 toybox echo rc=$?').Trim())
Say ('focus before: ' + (((Sh 'dumpsys window windows | grep -m1 mCurrentFocus')) -replace '\s+', ' ').Trim())

# A known state, not an inherited one: a fresh cold start always autoplays the feed.
Dev ('am force-stop ' + $tt)
Start-Sleep -Seconds 2
Dev ('am start -n ' + $ttAct)
Start-Sleep -Seconds 9

Probe 'playing1'
# Centre of a 1080x2275 screen. A single tap on the video toggles playback and touches nothing else.
Dev 'input tap 540 1200'
Start-Sleep -Seconds 2
Probe 'paused1'
Dev 'input tap 540 1200'
Start-Sleep -Seconds 2
Probe 'playing2'
Dev 'input tap 540 1200'
Start-Sleep -Seconds 2
Probe 'paused2'

Say ('focus after: ' + (((Sh 'dumpsys window windows | grep -m1 mCurrentFocus')) -replace '\s+', ' ').Trim())
Say 'DONE'

