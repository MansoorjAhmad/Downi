# _L_tiktok_control.ps1 - DEVICE_TEST 0z-8-7: the TikTok control, five taps, on the fixed build.
#
# WHY THIS EXISTS. 0z-8 measured the Instagram link-grab (5.01 s -> 1.62 s) and left TikTok
# unmeasured: the box export had torn the Fetcher down that time, so a run there would have
# measured nothing ("Outstanding, said plainly", 0z-8-7). The change touched cadences both
# platforms share (clipboard lead-in 350 -> 120 ms, surface poll quantum 250 -> 120 ms) and
# TikTok's plan is `ledger > copy_link`, so plain reading says TikTok can only be equal or
# faster - but "can only be" is not a measurement, and C5 is a TikTok cell. This is the
# measurement, taken the same way the Instagram side was.
#
# HOW A READING IS TAKEN (harness rules from DEVICE_TEST 0z-7, all of them learned by losing a run):
#   * a run is triggered by tapping the Core's own window (found live in `dumpsys window windows`,
#     never a remembered coordinate);
#   * this is a RELEASE build, and there the box file exists only through the UI Export - so every
#     tap is followed by `_J_export.ps1`, which asks the UI for the Export button (the card grows a
#     line as grabs accumulate, so a hardcoded y is a lie waiting to happen) and refuses to call a
#     stale file a reading;
#   * `monkey -c LAUNCHER` is refused by this ROM -> `am start -n` with the resolved activity;
#   * nothing here trusts a file it did not watch get written: `uiautomator dump` prints its own
#     message, and a dump that wrote nothing leaves the previous file in place for the `pull` to
#     fetch - a fossil tree, indistinguishable from a real one except by its freshness (see Dump());
#   * the handoff gate is deliberately NOT touched: deliverByTap() ignores it on purpose ("a
#     user-initiated download always goes through"), and with no spike_config.properties the
#     automatic dump path stays off - the safe setting 0z-6's rule demands.
#
# WHAT IT WRITES: _L\tiktok_control.txt (narration + every reading as it lands) and _J\tt<N>_box.txt
# (one exported box per tap, greppable with the same filter the Instagram runs used).
$ErrorActionPreference = 'Continue'
$repo  = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$adb   = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$cline = 'C:\Users\Manso\AppData\Local\Cline'
$dir   = Join-Path $cline '_L'
$log   = Join-Path $dir 'tiktok_control.txt'
$pkg   = 'com.omnidownloader.app'
$tt    = 'com.zhiliaoapp.musically'
$ttAct = 'com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Remove-Item $log -ErrorAction SilentlyContinue

function Say($m) { ((Get-Date).ToString('HH:mm:ss') + '  ' + $m) | Add-Content -Path $log -Encoding UTF8 }
# Dev, not CD: PowerShell's built-in Set-Location alias shadows a function named CD, which once
# turned every device command into a failed Set-Location (documented in _J_export.ps1).
function Sh($c)  { (cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul')) -join "`n" }
function Dev($c) { cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul') | Out-Null }

# The dump, with the fossil hunted out of it. This is the one part of the rig that was measured
# wrong on 2026-09-29 and it is worth spelling out, because the shape of the bug is what mattered:
# 15 of 15 dumps succeeded in every combination tried - a11y bound or not, video playing or paused,
# Core overlay up or not (_L\probe3/4/5.txt) - so neither the Fetcher's accessibility binding nor a
# moving video breaks `uiautomator dump`. What broke the run was silence: when a dump writes nothing
# (it prints an error instead of "hierchary dumped to"), whatever file was already at that path is
# what the `pull` fetches, and the rig then reported "TikTok is not showing a feed video" about a
# tree that had never been TikTok's - four byte-identical pulls of the app's own Diagnostics screen,
# handed back as four SKIPs. So: delete the file, read the dump's own message, demand a fresh tree
# that is actually TikTok's, retry, and when it still fails say DUMP FAILURE out loud. A failure is
# information; a fossil wearing the costume of a reading is a lie.
function Dump($name, $any = $false) {
    $local = Join-Path $dir ($name + '.xml')
    $dev   = '/sdcard/_L_rig_d.xml'
    for ($try = 1; $try -le 3; $try++) {
        Remove-Item $local -ErrorAction SilentlyContinue
        Dev ('rm -f ' + $dev)
        $o  = Sh ('timeout 25 uiautomator dump ' + $dev)
        $ok = ($o -match 'dumped to')
        $sz = (Sh ('stat -c %s ' + $dev)).Trim()
        Say ('  dump ' + $name + ' try ' + $try + ' of 3: ' + $(if ($ok) { 'dumped' } else { 'FAILED' }) + ' size=' + $sz + ' msg="' + ($o.Trim() -replace "`n", ' ') + '"')
        if ($ok) {
            cmd /c ('"' + $adb + '" pull ' + $dev + ' "' + $local + '" 2>nul') | Out-Null
            if (Test-Path $local) {
                # UTF-8 on purpose: uiautomator escapes some characters (emoji) as references but
                # writes others as literal UTF-8, and this shell's default Get-Content turns those
                # into mojibake ("Post 1Â |Â ..." in the probe) - fine for comparing two trees, fatal
                # for comparing a caption against a delivered filename.
                try { $x = [xml]([System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)) } catch { $x = $null; Say '    (that pull is not parseable XML)' }
                if ($x -ne $null) {
                    if ($any -or ($x.OuterXml -match [regex]::Escape($tt))) { return $x }
                    Say '    (fresh, but not a TikTok tree - refusing it rather than calling it a feed)'
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    Say '  NO FRESH TREE for ' + $name + ' after 3 tries'
    return $null
}

# The same fingerprint the auto-scroll proof used: TikTok's own title/desc/sound/like/share nodes.
# A tree with none of them is not evidence of a video, so it is reported as absent, never as "same".
function Fingerprint($xml) {
    if ($xml -eq $null) { return $null }
    $g = @{}
    foreach ($n in $xml.SelectNodes('//*[@bounds]')) {
        $id = ($n.'resource-id' -replace 'com.zhiliaoapp.musically:id/', '')
        if ($id -eq 'title' -or $id -eq 'desc') {
            $t = ($n.'text' -replace '\s+', ' ').Trim()
            if ($t) { $g[$id] = $t }
        }
        $d = $n.'content-desc'
        if ($d -match '^(Sound:|Like video|Share video|Read or add comments)') {
            $g[($d -split ' ')[0]] = ($d -replace '\s+', ' ').Trim()
        }
    }
    $keys = @('title', 'desc', 'Sound:', 'Like', 'Share', 'Read') | ForEach-Object { $k = $_; if ($g.ContainsKey($k)) { $k + '=' + $g[$k] } }
    if ($keys.Count -eq 0) { return $null }
    return ($keys -join ' | ')
}

# The Core's own window frame, read live. The tap goes to its centre - the same point the
# 2026-09-28 runs used (992,799 for frame [904,711][1080,887]).
function CoreCenter() {
    $lines = cmd /c ('"' + $adb + '" shell dumpsys window windows 2>nul')
    $idx = ($lines | Select-String -Pattern 'com\.omnidownloader\.app:[0-9a-f]+ u0 com\.omnidownloader\.app' | Select-Object -First 1).LineNumber
    if (-not $idx) { return $null }
    $blk = $lines[($idx - 1)..([Math]::Min($idx + 25, $lines.Count - 1))]
    $fr = ($blk | Select-String -Pattern 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]' | Select-Object -First 1).Matches[0]
    if (-not $fr) { return $null }
    $l = [int]$fr.Groups[1].Value; $t = [int]$fr.Groups[2].Value
    $r = [int]$fr.Groups[3].Value; $b = [int]$fr.Groups[4].Value
    return @{ x = [int](($l + $r) / 2); y = [int](($t + $b) / 2); frame = "$l,$t,$r,$b" }
}

function FolderFiles() {
    $ls = cmd /c ('"' + $adb + '" shell ls /sdcard/Movies/DOWNI 2>nul')
    return @($ls | Where-Object { $_ -and $_ -notmatch 'No such' } | ForEach-Object { $_.Trim() })
}

# The Fetcher must be bound or the tap has nothing behind it. An install wipes the binding, and
# this ROM wipes it on its own too, so it is asserted before every tap rather than once.
function EnsureFetcher() {
    $cur = (Sh 'settings get secure enabled_accessibility_services').Trim()
    if ($cur -notmatch 'DowniFetcherService') {
        Say '  the a11y binding is gone - re-arming it'
        Arm
        return (TestBound)
    }
    # A setting that says "bound" is a request, not a fact: this ROM drops the service without
    # clearing it, and the only proof that it is really running is the Core it draws. Without the
    # Core there is nothing to tap, so a missing Core with a "bound" setting gets re-armed too.
    if (-not (WaitCore 6)) {
        Say '  the setting says bound but the Fetcher has drawn no Core - re-arming the service'
        Dev 'settings delete secure enabled_accessibility_services'
        Start-Sleep -Seconds 2
        Arm
    }
    return (TestBound)
}

function Arm {
    Dev 'settings put secure accessibility_enabled 1'
    Dev ('settings put secure enabled_accessibility_services ' + $pkg + '/' + $pkg + '.DowniFetcherService')
    Start-Sleep -Seconds 5
}

# Bound AND drawing its Core - the two things a tap needs. probe5 measured the Core appearing 2 s
# after the bind, so the wait is generous rather than tight.
function TestBound {
    $s = (Sh 'settings get secure enabled_accessibility_services').Trim()
    if ($s -notmatch 'DowniFetcherService') { return $false }
    return [bool](WaitCore 10)
}

# The Core's own window, waited for instead of demanded. Returns the same shape CoreCenter does.
function WaitCore([int]$seconds = 12) {
    for ($k = 1; $k -le [int]($seconds / 2); $k++) {
        $c = CoreCenter
        if ($c) { return $c }
        Start-Sleep -Seconds 2
    }
    return $null
}

function EnsureTikTok() {
    $focus = (((Sh 'dumpsys window') -split "`n" | Select-String 'mCurrentFocus' | Select-Object -First 1) -join '')
    if ($focus -notmatch [regex]::Escape($tt)) {
        Dev ('am start -n ' + $ttAct)
        Start-Sleep -Seconds 8
    }
}

function ExportBox($tag) {
    & (Join-Path $cline '_J_export.ps1') -Tag $tag 2>&1 | ForEach-Object { Say ('    export: ' + $_) }
}

function RunLines($tag) {
    $box = Join-Path $cline ('_J\' + $tag + '_box.txt')
    if (-not (Test-Path $box)) { return @('(no box file)') }
    return @(Get-Content $box | Select-String 'CORE_TOUCH|TAP_SCAN pkg|RUN_START|CHAIN_SURFACE|CHAIN_COPYLINK_CANDIDATE|CHAIN_TARGET_CLICK|CHAIN_CLIP_TRY|CHAIN_CLIP_SAME|CHAIN_CLIPBOARD|CHAIN_LATENCY|CHAIN_DELIVER_OK|CHAIN_DELIVER_DUP|RUN_END|ENGINE_JOB_START' |
        ForEach-Object { '    ' + $_.Line.Trim().Substring(0, [Math]::Min(220, $_.Line.Trim().Length)) })
}

$taps = 0
Say ('START version=' + (((Sh ('dumpsys package ' + $pkg)) -split "`n" | Select-String 'versionName' | Select-Object -First 1) -join '').Trim())
Say ('a11y=' + (Sh 'settings get secure enabled_accessibility_services').Trim())
Say ('gate file=' + (Sh 'cat /sdcard/Android/data/com.omnidownloader.app/files/fetch-spike/spike_config.properties').Trim() + '  (absent = auto path off, the safe setting)')

for ($i = 1; $i -le 10; $i++) {
    if ($taps -ge 5) { break }
    Say ('--- attempt ' + $i + ' (tap ' + ($taps + 1) + ' of 5) ---')
    if (-not (EnsureFetcher)) { Say 'ABORT: the Fetcher would not bind'; break }
    EnsureTikTok

    # A deliberate swipe for the next video, done HERE - while TikTok is the foreground app. The
    # auto-scroll fix means the feed no longer moves by itself, so without this the next tap would
    # re-tap the same URL and the run would end in CHAIN_DELIVER_DUP (a weaker reading, and one that
    # reads as delivered=false for a reason that has nothing to do with the chain's speed). The
    # first attempt of a session needs no swipe: it starts wherever the owner left the feed.
    if ($taps -ge 1) {
        Dev 'input swipe 540 1700 540 700 220'
        Start-Sleep -Seconds 5
    }

    $b = Dump ('before_' + $i)
    # A tree that could not be dumped says nothing about TikTok, so it must never be dressed up as a
    # verdict on TikTok (that mistake cost four SKIPs and a wrong root cause on 2026-09-29).
    if ($b -eq $null) { Say 'SKIP: DUMP FAILURE - no fresh tree (see the dump lines above); nothing was learned about TikTok'; Start-Sleep -Seconds 5; continue }
    $fpB = Fingerprint $b
    # A sheet left open from a previous run would swallow the tap; the script is the user here.
    if ($fpB -eq $null -and $b -ne $null) {
        $raw = [System.IO.File]::ReadAllText((Join-Path $dir ('before_' + $i + '.xml')), [System.Text.Encoding]::UTF8)
        if ($raw -match 'Copy link|Send to|Share to') {
            Say '  a sheet is open - dismissing it as the user would'
            Dev 'input keyevent KEYCODE_BACK'
            Start-Sleep -Seconds 2
            $b = Dump ('before_' + $i); $fpB = Fingerprint $b
        }
    }
    if ($fpB -eq $null) { Say 'SKIP: a fresh TikTok tree, but no feed video in it (no title/desc/sound nodes - a sheet or another screen is on top)'; Start-Sleep -Seconds 4; continue }
    Say ('BEFORE ' + $fpB)

    $core = WaitCore 12
    if ($core -eq $null) { Say 'SKIP: the Core is not on screen (nothing to tap - no reading is possible)'; Start-Sleep -Seconds 4; continue }
    Say ('CORE frame=' + $core.frame + ' tap=' + $core.x + ',' + $core.y)

    $filesBefore = FolderFiles
    $t0 = (Get-Date)
    Dev ('input tap ' + $core.x + ' ' + $core.y)
    Say ('TAPPED at ' + (Get-Date).ToString('HH:mm:ss'))
    $taps++
    $landed = $null
    for ($w = 1; $w -le 5; $w++) {
        Start-Sleep -Seconds 3
        $new = @(FolderFiles | Where-Object { $filesBefore -notcontains $_ })
        if ($new.Count -gt 0) { $landed = $new -join '; '; break }
    }
    $secs = [int]((Get-Date) - $t0).TotalSeconds
    # The kill check belongs HERE - before the export. The export opens the app and taps Settings,
    # and this ROM is documented to read `pidof` empty around transport hiccups, so a check taken
    # after it cannot tell "the vendor killed us" from "adb blinked" (0z-7's harness note).
    # `pidof` empty is a SUSPICION, not a finding: this ROM reads it empty around transport hiccups
    # (0z-7), and on 2026-09-29 it read empty on taps that then exported a fresh box - the app was
    # alive the whole time. Say which it is, and let the export below be the evidence either way.
    if (-not (Sh 'pidof ' + $pkg).Trim()) { Say 'pidof came back EMPTY (vendor kill, or the documented transport blink - the export decides)' }
    $tag = 'tt' + $taps
    ExportBox $tag
    foreach ($l in (RunLines $tag)) { Say $l }
    $boxLines = Get-Content (Join-Path $cline ('_J\' + $tag + '_box.txt')) -ErrorAction SilentlyContinue
    $lat = @($boxLines | Select-String 'CHAIN_LATENCY')
    if ($lat.Count) { Say ('LATENCY ' + $lat[$lat.Count - 1].Line.Trim()) } else { Say 'NO CHAIN_LATENCY LINE IN THE BOX - this run has no reading' }
    if ($landed) { Say ('DELIVERED after ' + $secs + 's: ' + $landed) } else { Say ('NO NEW FILE after ' + $secs + 's (the chain may still have delivered - the box decides)') }

    # Ask the feed the same question it was asked before the tap. The export leaves the Fetcher's own
    # screen in front, so without bringing TikTok back this line could only ever say "no tree" - and a
    # check that cannot fail is not a check. `$true` = accept any package, because "the app is in
    # front" is a legitimate answer here and Fingerprint() returns null for it.
    EnsureTikTok
    $a = Dump ('after_' + $i) $true; $fpA = Fingerprint $a
    if ($fpA -eq $null) { Say 'AFTER  (no feed tree - the app is still in front, or the dump failed)' }
    elseif ($fpA -eq $fpB) { Say 'AFTER  the same video is on screen' }
    else { Say 'AFTER  a new video is on screen' }
    Start-Sleep -Seconds 3
}
Say 'DONE'
