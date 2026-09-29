# _L_rig_selftest.ps1 - runs the rig's OWN functions against the live device, without starting a run.
#
# The function bodies are lifted from _L_tiktok_control.ps1 verbatim (everything from `function Say`
# up to the executable `$taps = 0` line) and evaluated here with the same variables the rig sets, so
# what is tested is the code that will run - not a retyped copy. It then asks the device the four
# questions the run depends on: is the Core there, can a fresh TikTok tree be dumped, does the tree
# fingerprint, and does EnsureFetcher agree that the Fetcher is up. No taps, no state changes beyond
# the a11y binding the run needs anyway.
$ErrorActionPreference = 'Continue'
$rig  = 'C:\Users\Manso\AppData\Local\Cline\_L_tiktok_control.ps1'
$repo = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$cline = 'C:\Users\Manso\AppData\Local\Cline'
$src   = Get-Content $rig -Raw
$start = $src.IndexOf('function Say')
$end   = $src.IndexOf('$taps = 0')
if ($start -lt 0 -or $end -lt 0) { Write-Output 'FATAL: could not locate the function block'; exit 1 }

# The variables the rig sets above its functions - same values, so the functions behave identically.
$adb   = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$dir   = Join-Path $cline '_L'
$log   = Join-Path $dir 'selftest.txt'
$pkg   = 'com.omnidownloader.app'
$tt    = 'com.zhiliaoapp.musically'
$ttAct = 'com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Remove-Item $log -ErrorAction SilentlyContinue

Invoke-Expression ($src.Substring($start, $end - $start))

Say '=== selftest: the rig''s own functions, against the live device ==='
Say ('version=' + (((Sh ('dumpsys package ' + $pkg)) -split "`n" | Select-String 'versionName' | Select-Object -First 1) -join '').Trim())
Say ('a11y setting=' + (Sh 'settings get secure enabled_accessibility_services').Trim())

Say ('EnsureFetcher -> ' + (EnsureFetcher))
$c = CoreCenter
Say ('CoreCenter -> ' + $(if ($c) { 'frame=' + $c.frame + ' tap=' + $c.x + ',' + $c.y } else { 'NULL' }))
$w = WaitCore 6
Say ('WaitCore 6 -> ' + $(if ($w) { 'frame=' + $w.frame } else { 'NULL' }))

$d = Dump 'selftest_before'
if ($d) {
    $pk = @($d.SelectNodes('//*[@package]') | ForEach-Object { $_.package } | Sort-Object -Unique) -join ','
    Say ('Dump -> tree accepted, packages=' + $pk + ', nodes=' + $d.SelectNodes('//*[@bounds]').Count)
    $f = Fingerprint $d
    Say ('Fingerprint -> ' + $(if ($f) { $f } else { 'NULL' }))
} else { Say 'Dump -> NULL (the gate refused everything three times - that is a failure, and it says so)' }

$d2 = Dump 'selftest_any' $true
Say ('Dump -AnyPackage -> ' + $(if ($d2) { 'tree accepted' } else { 'NULL' }))
Say '=== selftest done (no taps were made) ==='
Get-Content $log
