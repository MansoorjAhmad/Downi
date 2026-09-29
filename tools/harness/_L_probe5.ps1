# _L_probe5.ps1 - the last untested condition: does the dump work with the Core overlay UP?
#
# WHY THIS EXISTS. Probe4 showed 12/12 dumps fine (3-6 s) with a11y bound and the video moving - so
# neither a11y nor playback breaks `uiautomator dump`. One difference remains between probe4 and a
# real tap run: in a real run the Core's floating overlay is on screen, and that overlay carries a
# live readout (status/speed) whose text changes continuously. A view whose *content* keeps changing
# is the classic way to keep the accessibility tree from ever going idle, and a dump that never sees
# an idle tree fails with "could not get idle state" and WRITES NOTHING - leaving whichever file was
# already at that path for the next `pull` to fetch. That is exactly the shape of the four SKIPs.
# So this probe binds a11y (which is what raises the Core), WAITS for the window, then dumps three
# times and reports the dump's own message verbatim - success or error - with the overlay confirmed
# present at each attempt. It leaves a11y BOUND, which is the state a tap run needs anyway.
$ErrorActionPreference = 'Continue'
$repo  = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$adb   = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$cline = 'C:\Users\Manso\AppData\Local\Cline'
$dir   = Join-Path $cline '_L'
$log   = Join-Path $dir 'probe5.txt'
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
    for ($k = 1; $k -le 3; $k++) { $hs += (CenterHash (Cap ('p5_m_' + $k + '.png'))); if ($k -lt 3) { Start-Sleep -Milliseconds 900 } }
    $d = ($hs | Sort-Object -Unique).Count
    return $(if ($d -gt 1) { 'MOVING' } else { 'STILL' })
}

# The rig's own CoreCenter() logic, so this probe answers for the code that will run.
function CoreWin {
    $o = cmd /c ('"' + $adb + '" shell dumpsys window windows 2>nul')
    $i = ($o | Select-String -Pattern 'com\.omnidownloader\.app:[0-9a-f]+ u0 com\.omnidownloader\.app' | Select-Object -First 1)
    if (-not $i) { return $null }
    $blk = $o[($i.LineNumber - 1)..([Math]::Min($i.LineNumber + 25, $o.Count - 1))]
    $f = $blk | Select-String -Pattern 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]' | Select-Object -First 1
    if (-not $f) { return @{ frame = '(window present, NO frame line)'; x = 0; y = 0 } }
    $g = $f.Matches[0].Groups
    $l = [int]$g[1].Value; $t = [int]$g[2].Value; $r = [int]$g[3].Value; $b = [int]$g[4].Value
    return @{ frame = "$l,$t,$r,$b"; x = [int](($l + $r) / 2); y = [int](($t + $b) / 2) }
}

# The dump message is the evidence: "UI hierchary dumped to: X" means a file was written;
# anything else (notably "could not get idle state") means the pull is about to hand back a fossil.
function DumpOnce($tag) {
    $dev = '/sdcard/_L_p5_' + $tag + '.xml'
    Dev ('rm -f ' + $dev)
    $t0 = Get-Date
    $o  = Sh ('timeout 25 uiautomator dump ' + $dev)
    $el = [int]((Get-Date) - $t0).TotalSeconds
    $sz = (Sh ('stat -c %s ' + $dev)).Trim()
    $msg = ($o.Trim() -replace "`n", ' ')
    Say ('    dump[' + $tag + '] ' + $el + 's size=' + $sz + ' msg="' + $msg + '"')
}

Say '=== binding a11y (this is what raises the Core) and waiting for the window ==='
Dev 'settings put secure accessibility_enabled 1'
Dev ('settings put secure enabled_accessibility_services ' + $pkg + '/' + $pkg + '.DowniFetcherService')
$seen = $false
for ($s = 1; $s -le 15; $s++) {
    Start-Sleep -Seconds 2
    $cw = CoreWin
    if ($cw) { Say ('  t+' + ($s * 2) + 's CORE PRESENT frame=' + $cw.frame + ' tap=' + $cw.x + ',' + $cw.y); $seen = $true; break }
    Say ('  t+' + ($s * 2) + 's no Core window yet')
}
if (-not $seen) { Say '  the Core never appeared within 30s - worth knowing before the run' }
Say ('  settings enabled_accessibility_services = ' + (Sh 'settings get secure enabled_accessibility_services').Trim())

Dev ('am force-stop ' + $tt)
Start-Sleep -Seconds 2
Dev ('am start -n ' + $ttAct)
Start-Sleep -Seconds 9
$cw = CoreWin
Say ('  Core at dump time: ' + $(if ($cw) { 'frame=' + $cw.frame + ' tap=' + $cw.x + ',' + $cw.y } else { 'ABSENT' }))
Say ('  motion: ' + (Motion))
for ($i = 1; $i -le 3; $i++) { DumpOnce ('core_' + $i) }
$cw = CoreWin
Say ('  Core after dumps: ' + $(if ($cw) { 'frame=' + $cw.frame } else { 'ABSENT' }))
Say ('focus at end: ' + (((Sh 'dumpsys window') -split "`n" | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '').Trim())
Say 'DONE (a11y left BOUND, Core expected up - the state a tap run needs)'

