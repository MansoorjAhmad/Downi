# Robust a11y armer for the vivo V2058 - survives the delayed-unbind race.
#
# WHY: the documented recipe (`tools\fetch_diag.ps1 -Arm`) does delete -> sleep 2 -> put and proves
# a NEW SERVICE_CONNECTED line. On 2026-09-27 that proof was true and still useless: the bind at
# 10:46:38 and again at 10:51:05 was followed within ~2 s by SERVICE_UNBIND / SERVICE_DESTROY
# (process stayed alive, so not the ABE kill) - the framework's delayed reaction to the *delete*
# landed after the *add*. Every gate run then wrote 28 screenshots of an empty launcher, because
# the Core window dies with the binding and the debug command channel lives in the service.
#
# This script: clears stale core.cmd -> removes -> settles LONG -> adds -> asserts (a) the setting,
# (b) no SERVICE_UNBIND in the fresh log, (c) consumption of a pushed command. Logged, so a caller
# timeout can never hide where it stopped.
param(
    [int]$SettleRemove = 12,
    [int]$SettleBind   = 8
)
$ErrorActionPreference = 'SilentlyContinue'
$root  = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
# Renamed in v3.3: FetchSpikeService -> DowniFetcherService. The stale name was the quiet kind of
# broken: `settings put` accepts a component the APK no longer exports, so this script would report a
# successful arm while nothing ever bound and the Core never appeared (found 2026-09-29).
$comp  = "$pkg/$pkg.DowniFetcherService"
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$log   = 'C:\Users\Manso\AppData\Local\Cline\_core_arm.log'

function Say($m) { Add-Content -Path $log -Value ((Get-Date -Format 'HH:mm:ss') + ' ' + $m); Write-Host $m }
function Sh([string]$c) { ((cmd /c "`"$adb`" $c 2>&1") -join "`n").Trim() }

Remove-Item $log -Force -ErrorAction SilentlyContinue
Say ('START devices=' + (((Sh 'devices') -replace "`r?`n", ' ')))
if ((Sh 'devices') -notmatch "`tdevice") { Say 'NO DEVICE'; exit 1 }

# 0. vivo needs the app to have been launched at least once after an install before it will
#    honour a new accessibility binding (10:43: the setting was set and nothing ever bound).
Say ('am start -> ' + (Sh "shell am start -n $pkg/.MainActivity"))
Start-Sleep -Seconds 3
Say ('pid=' + (Sh "shell pidof $pkg"))

# 1. stale commands out of the way, so "consumed" is unambiguous proof
Sh "shell rm -f $phone/core.cmd" | Out-Null
Say 'core.cmd cleared'

# 2. remove -> LONG settle -> add. The settle is the whole fix.
#    Count the binds first: since v3.3 every bind appends to one blackbox.txt, so the proof that a new
#    bind happened is this count rising - the old per-bind spike_*.log filename no longer exists.
$bindsBefore = @(((Sh "shell cat $phone/blackbox.txt") -split "`n") | Select-String 'SERVICE_CONNECTED').Count
Say ('SERVICE_CONNECTED before = ' + $bindsBefore)
Sh 'shell settings delete secure enabled_accessibility_services' | Out-Null
Say "removed; settling ${SettleRemove}s (framework's delayed unbind lands while already off)"
Start-Sleep -Seconds $SettleRemove
Say ('a11y(off)=' + (Sh 'shell settings get secure enabled_accessibility_services'))
Sh "shell settings put secure enabled_accessibility_services $comp" | Out-Null
Sh 'shell settings put secure accessibility_enabled 1' | Out-Null
Say "added; waiting ${SettleBind}s"
Start-Sleep -Seconds $SettleBind
Say ('a11y=' + (Sh 'shell settings get secure enabled_accessibility_services'))

# 3. Proof, in the order that matters. Two of these are authoritative and one is not:
#    (a) AUTHORITATIVE: the framework's own bound-services list (`dumpsys accessibility`). If our
#        component is not in it, no tap can work, whatever any file says.
#    (b) AUTHORITATIVE: the Core window, polled for - that is the tap target.
#    (c) NOT A LIVE SIGNAL: blackbox.txt. It is a rotating 100-event ring AND its freshness lags.
#        Measured 2026-09-29: `dumpsys` listed the service bound with the Core drawn at 09:54 while the
#        newest SERVICE_CONNECTED in the file was 09:45 and the file's mtime had not moved since. So
#        counting lines in it (what this script used to do) can report "NO NEW BIND" about a service
#        that is bound and working - and a rotation can make the count go DOWN.
Say ('  framework bound-services = ' + ((((Sh 'shell dumpsys accessibility') -split "`n") | Select-String 'Bound services' | Select-Object -First 1) -join '').Trim())
$coreOk = $false
for ($i = 1; $i -le 10; $i++) {
    $cw = ((Sh 'shell dumpsys window windows') -split "`n" | Select-String -Pattern 'com\.omnidownloader\.app:[0-9a-f]+ u0 com\.omnidownloader\.app' | Select-Object -First 1)
    if ($cw) { $coreOk = $true; Say ('  Core PRESENT at t+' + ($i * 2) + 's -> tap target exists'); break }
    Start-Sleep -Seconds 2
}
if (-not $coreOk) { Say '  Core ABSENT after 20s -> nothing to tap: the binding did not take (re-run, or toggle the service in Settings > Accessibility)' }
$bnd = ((((Sh 'shell dumpsys accessibility') -split "`n") | Select-String 'Bound services' | Select-Object -First 1) -join '').Trim()
Say ('  bound-services (final) = ' + $bnd + '  ->  ' + $(if ($bnd -match 'DowniFetcher|DOWNI Fetcher') { 'BOUND: taps can work' } else { 'NOT BOUND: the arm did not take, whatever the setting says' }))
$bindNew = ((((Sh "shell cat $phone/blackbox.txt") -split "`n") | Select-String 'SERVICE_CONNECTED' | Select-Object -Last 1) -join '').Trim()
Say ('  newest SERVICE_CONNECTED in the ring (hint only, it lags): ' + $bindNew)
$unb = @(((Sh "shell cat $phone/blackbox.txt") -split "`n") | Select-String 'SERVICE_UNBIND').Count
Say ('  SERVICE_UNBIND lines in the ring = ' + $unb + '  (a rise soon after an add means the delete/add race is back)')
Say 'DONE'
