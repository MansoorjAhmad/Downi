# _L_probe4.ps1 - the two-axis test: does `uiautomator dump` care about the PLAYING video, or about
# the Fetcher's accessibility service being bound?
#
# WHY THIS EXISTS. Probe3 dumped fine (3 s, four times) on a *playing* feed with a11y OFF, which
# falsifies "a playing video breaks the dump". But the rig that saw SKIPs always ran EnsureFetcher
# first, so a11y was bound. Two candidate causes, one test:
#     axis A: video playing vs paused   (pixel-hash discriminator, proven in probe3)
#     axis B: Fetcher a11y bound vs not
# Three timed dumps per cell, each under device-side `timeout 20`, so a hang is measured instead of
# ending the run. This is the honest way to pick the rig's dump strategy: whichever cell hangs is the
# one the rig has to avoid, and the rig must tap with a11y BOUND - so if that cell hangs, the dump
# has to move, not the binding.
$ErrorActionPreference = 'Continue'
$repo  = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$adb   = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$cline = 'C:\Users\Manso\AppData\Local\Cline'
$dir   = Join-Path $cline '_L'
$log   = Join-Path $dir 'probe4.txt'
$pkg   = 'com.omnidownloader.app'
$tt    = 'com.zhiliaoapp.musically'
$ttAct = 'com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Remove-Item $log -ErrorAction SilentlyContinue

function Say($m) { ((Get-Date).ToString('HH:mm:ss') + '  ' + $m) | Add-Content -Path $log }
function Sh($c)  { (cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul')) -join "`n" }
function Dev($c) { cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul') | Out-Null }

Add-Type -AssemblyName System.Drawing
function Cap([string]$name) {
    $png = Join-Path $dir $name
    Remove-Item $png -ErrorAction SilentlyContinue
    cmd /c ('"' + $adb + '" exec-out screencap -p > "' + $png + '"') | Out-Null
    return $png
}
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
    return ([BitConverter]::ToString($md5.ComputeHash([System.Text.Encoding]::ASCII.GetBytes($sb.ToString()))) -replace '-', '').Substring(0, 12)
}
function Motion {
    $hs = @()
    for ($k = 1; $k -le 3; $k++) { $hs += (CenterHash (Cap ('p4_m_' + $k + '.png'))); if ($k -lt 3) { Start-Sleep -Milliseconds 900 } }
    $d = ($hs | Sort-Object -Unique).Count
    return @{ label = $(if ($d -gt 1) { 'MOVING' } else { 'STILL' }); hashes = ($hs -join ' ') }
}
function DumpOnce($tag) {
    $dev = '/sdcard/_L_p4_' + $tag + '.xml'
    Dev ('rm -f ' + $dev)
    $t0 = Get-Date
    $o  = Sh ('timeout 20 uiautomator dump ' + $dev)
    $el = [int]((Get-Date) - $t0).TotalSeconds
    $ok = ($o -match 'dumped to')
    $sz = (Sh ('stat -c %s ' + $dev)).Trim()
    Say ('    dump[' + $tag + '] ' + $el + 's ok=' + $ok + ' size=' + $sz)
    return $ok
}

# Is a service really bound right now? The setting string is a request; this is the answer.
function BoundNow {
    $l = (Sh 'dumpsys accessibility') -split "`n" | Select-String -Pattern 'Bound services|Service\[|DowniFetcher'
    $o = @($l | ForEach-Object { $_.Line.Trim() } | Where-Object { $_ })
    if ($o.Count -eq 0) { return 'Bound services:{} (nothing bound)' }
    return ($o -join ' / ').Substring(0, [Math]::Min(160, ($o -join ' / ').Length))
}

Say '=== binding the Fetcher a11y service (what a real tap run needs) ==='
Dev 'settings put secure accessibility_enabled 1'
Dev ('settings put secure enabled_accessibility_services ' + $pkg + '/' + $pkg + '.DowniFetcherService')
Start-Sleep -Seconds 6
Say ('  settings enabled_accessibility_services = ' + (Sh 'settings get secure enabled_accessibility_services').Trim())
$ax = (Sh 'dumpsys accessibility') -split "`n" | Select-String -Pattern 'Bound services|Service\[|DowniFetcher'
foreach ($l in $ax) { if ($l.Line.Trim()) { Say ('  dumpsys: ' + $l.Line.Trim()) } }

Dev ('am force-stop ' + $tt)
Start-Sleep -Seconds 2
Dev ('am start -n ' + $ttAct)
Start-Sleep -Seconds 9

Say '=== cell 1: a11y BOUND, video PLAYING ==='
Say ('  bound: ' + (BoundNow))
$m = Motion
Say ('  motion: ' + $m.label + '  (' + $m.hashes + ')')
for ($i = 1; $i -le 3; $i++) { DumpOnce ('bound_playing_' + $i) | Out-Null }

Say '=== cell 2: a11y BOUND, video PAUSED ==='
Say ('  bound: ' + (BoundNow))
Dev 'input tap 540 1200'
Start-Sleep -Seconds 2
$m = Motion
Say ('  motion: ' + $m.label + '  (' + $m.hashes + ')')
for ($i = 1; $i -le 3; $i++) { DumpOnce ('bound_paused_' + $i) | Out-Null }

Say '=== cell 3: a11y UNBOUND, video still PAUSED ==='
Dev 'settings delete secure enabled_accessibility_services'
Dev 'settings put secure accessibility_enabled 0'
Start-Sleep -Seconds 5
Say ('  settings enabled_accessibility_services = ' + (Sh 'settings get secure enabled_accessibility_services').Trim())
Say ('  bound: ' + (BoundNow))
$m = Motion
Say ('  motion: ' + $m.label + '  (' + $m.hashes + ')')
for ($i = 1; $i -le 3; $i++) { DumpOnce ('unbound_paused_' + $i) | Out-Null }

Say '=== cell 4: a11y UNBOUND, video PLAYING ==='
Say ('  bound: ' + (BoundNow))
Dev 'input tap 540 1200'
Start-Sleep -Seconds 2
$m = Motion
Say ('  motion: ' + $m.label + '  (' + $m.hashes + ')')
for ($i = 1; $i -le 3; $i++) { DumpOnce ('unbound_playing_' + $i) | Out-Null }

$foc = ((Sh 'dumpsys window') -split "`n" | Select-String 'mCurrentFocus' | Select-Object -First 1)
Say ('focus at end: ' + ($foc -join '').Trim())
Say 'DONE'
