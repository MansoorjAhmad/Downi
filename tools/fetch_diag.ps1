# One-shot on-device forensics for the V3.2 Fetcher spike.
# Built after the 2026-09-25 hunt where the process vanished ~2 min into a session and
# enabled_accessibility_services was wiped to null: this prints the whole picture (crash
# buffer FIRST) instead of guessing one command at a time.
#
#   (no switch)  full report
#   -Arm         re-enable FetchSpikeService (every install wipes it) + whitelist the app.
#                ALWAYS forces a real re-bind and then asserts it, because the handoff gate is
#                read only in onServiceConnected() (see below).
#   -Handoff X   with -Arm: write `handoff=true|false` into spike_config.properties first, then
#                re-bind and prove the live process actually read value X.
#   -Pull        pull fetch-spike/ (spike logs + shots) into test_out\spike-logs
#
# WHY -Arm FORCES A RE-BIND (found 2026-09-25, cost an hour): the gate is read only in
# onServiceConnected(), so rewriting spike_config.properties changes nothing in the running
# process. Toggling the binding is the only way to make it re-read — and `settings put` with the
# SAME value is a no-op (the framework sees no change, so no new bind happens). An "arm" that
# appends a component which is already present therefore silently does nothing: the file said
# handoff=true while every live run kept handoff=false. -Arm now removes the component, re-adds
# it, and fails loudly if no new SERVICE_CONNECTED line appears.
#
# For a death that happens while nobody is watching, start tools\soak_watch.ps1 -Minutes 30
# FIRST (it timestamps ALIVE/DEAD + a11y + heartbeat age every 20 s), then run this afterwards.
#
# Usage (this box needs the bypass):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_diag.ps1 -Arm
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_diag.ps1 -Arm -Handoff true
param(
    [switch]$Arm,
    [switch]$Pull,
    [string]$Handoff
)

$ErrorActionPreference = 'SilentlyContinue'
$root  = Split-Path -Parent $PSScriptRoot
$adb   = Join-Path $root 'android-sdk\platform-tools\adb.exe'
$pkg   = 'com.omnidownloader.app'
$comp  = "$pkg/com.omnidownloader.app.FetchSpikeService"
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$out   = Join-Path $root 'test_out\spike-logs'

function Sh([string]$cmd) { ((cmd /c "`"$adb`" $cmd 2>&1") -join "`n").Trim() }
function Head([string]$t) { Write-Host ''; Write-Host "===== $t =====" }

if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb"; exit 1 }

$dev = Sh 'devices'
Head 'DEVICES'
Write-Host $dev
if ($dev -notmatch "`tdevice") {
    Write-Host ''
    Write-Host 'No device. Plug the phone into USB and run tools\wifi_adb.ps1 first.'
    exit 1
}

# ---------- 1. crash buffer: the reason always lives here if it was a Java crash ----------
Head 'CRASH BUFFER (newest FATAL EXCEPTION block)'
$crash = Sh 'logcat -d -b crash'
$lines = $crash -split "`n"
$idx = -1
for ($i = $lines.Count - 1; $i -ge 0; $i--) { if ($lines[$i] -match 'FATAL EXCEPTION') { $idx = $i; break } }
if ($idx -ge 0) {
    Write-Host (($lines[$idx..([Math]::Min($lines.Count - 1, $idx + 26))]) -join "`n")
} else {
    Write-Host '(no FATAL EXCEPTION in the crash buffer — a kill without a Java crash)'
}

Head 'AndroidRuntime (main buffer, newest 10)'
$rt = (Sh 'logcat -d -b main') -split "`n" | Select-String -Pattern 'AndroidRuntime|BUBBLE_|CHAIN_ERR|CHAIN_STEP_ERR|SERVICE_|INTERRUPT'
Write-Host (($rt | Select-Object -Last 10) -join "`n")

# ---------- 2. process / binding / window: which of H1/H2/H4 is true ----------
# Filtering happens in PowerShell: a `| grep` after `adb shell …` is executed by cmd.exe on
# WINDOWS (where grep does not exist) and silently returns nothing — that blanked half of the
# first real run of this script (2026-09-25).
Head 'PROCESS'
$psOut = @((Sh 'shell ps -A') -split "`n" | Select-String -Pattern $pkg)
if ($psOut.Count) { Write-Host ($psOut -join "`n") } else { Write-Host '(no process — the app is not running)' }

Head 'ACCESSIBILITY BINDING'
Write-Host ('enabled_accessibility_services = ' + (Sh 'shell settings get secure enabled_accessibility_services'))
Write-Host ('accessibility_enabled          = ' + (Sh 'shell settings get secure accessibility_enabled'))
$acc = @((Sh 'shell dumpsys accessibility') -split "`n" | Select-String -Pattern 'Service\[|omnidownloader|bound|mIsEnabled')
Write-Host (($acc | Select-Object -First 20) -join "`n")

Head 'OUR WINDOWS (WMS)'
$w = Sh 'shell dumpsys window windows'
Write-Host (($w -split "`n" | Select-String -Pattern 'omnidownloader|ACCESSIBILITY_OVERLAY|FetcherSpike' | Select-Object -First 10) -join "`n")

# ---------- 3. why the OS may be killing us (H1) ----------
Head 'BATTERY / KILL STATE'
$idle = @((Sh 'shell dumpsys deviceidle whitelist') -split "`n" | Select-String -Pattern $pkg)
if ($idle.Count) { Write-Host ('deviceidle whitelist: ' + ($idle -join ' ')) } else { Write-Host 'deviceidle whitelist: NOT whitelisted' }
Write-Host ('appops RUN_IN_BACKGROUND: ' + (Sh "shell cmd appops get $pkg RUN_IN_BACKGROUND"))
Write-Host ('appops WAKE_LOCK      : ' + (Sh "shell cmd appops get $pkg WAKE_LOCK"))
# `-b all`, NOT `-b events`: on this ROM (vivo V2058) the events buffer rotates within minutes,
# so the line that actually matters — e.g. `am_kill … due to stop by com.vivo.abe` — is usually
# already gone by the time anyone looks. That single mistake cost a whole diagnostic session
# (2026-09-25): the deaths looked "unexplained" while the answer sat in `-b all`.
# NOTE: the trigger broadcast (`action.vivo.powercontrol` from `com.vivo.pem`) does NOT mention
# our package, so the first filter must accept the vivo side too — otherwise the leading indicator
# of a vendor kill is filtered away before we ever see it.
$ev = (Sh 'logcat -d -b all') -split "`n" | Select-String -Pattern 'omnidownloader|vivo\.pem|vivo\.abe|powercontrol' | Select-String -Pattern 'am_kill|am_proc_died|am_anr|am_crash|am_restart|am_proc_bound|powercontrol|Force stopping|force-stop'
Write-Host 'kill / power-control events (newest 16, read from -b all):'
Write-Host (($ev | Select-Object -Last 16) -join "`n")

# ---------- 3b. THE HANDOFF GATE (danger: a live gate fires real downloads) ----------
# Found 2026-09-25: this phone carried `handoff=true`, and nothing in the tooling said so — a scan
# session with the gate open can start a real grab behind your back. Print it loudly, every run.
Head 'HANDOFF GATE (spike_config.properties -> handoff=true means scans can start REAL grabs)'
$cfg = Sh "shell cat $phone/spike_config.properties"
if ($cfg -match 'handoff\s*=\s*true') {
    Write-Host '  !! handoff = TRUE  — the pipeline experiment is ARMED on this device.'
    Write-Host '     Any HIGH-confidence URL found by a dump path fires DowniDownloadService.startShared.'
    Write-Host ('     Disarm:  adb shell "echo handoff=false > ' + $phone + '/spike_config.properties"')
} elseif ($cfg -match 'handoff\s*=\s*false') {
    Write-Host '  handoff = false  (safe: the spike only logs)'
} else {
    Write-Host '  no spike_config.properties — code default applies (handoff = false, safe)'
}

# ---------- 4. the spike's own last words ----------
Head 'SPIKE LOG (newest file, last 14 lines)'
$ls = Sh "shell ls -t $phone"
$newest = (($ls -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1)
if ($newest) {
    $name = $newest.Trim()
    Write-Host "file: $name"
    $body = @((Sh "shell cat $phone/$name") -split "`n")
    Write-Host (($body | Select-Object -Last 14) -join "`n")
} else {
    Write-Host 'no spike log found — was the service ever bound?'
}

# ---------- 5. optional actions ----------
if ($Arm) {
    Head 'ARM (whitelist + FORCE A RE-BIND so the gate is re-read)'
    Write-Host (Sh "shell dumpsys deviceidle whitelist +$pkg")
    Write-Host (Sh "shell cmd appops set $pkg RUN_IN_BACKGROUND allow")

    # 1) write the gate file. Called through PowerShell, NOT Sh: Sh goes via cmd.exe, which would
    #    see the '>' and redirect on WINDOWS instead of on the phone ("The system cannot find the
    #    path specified") — that silently left handoff=false in the file on 2026-09-25 17:1x.
    if ($Handoff -eq 'true' -or $Handoff -eq 'false') {
        $null = & $adb shell "echo handoff=$Handoff > $phone/spike_config.properties"
        Write-Host ('  gate file now = ' + (Sh "shell cat $phone/spike_config.properties"))
    } else {
        Write-Host ('  gate file     = ' + (Sh "shell cat $phone/spike_config.properties") + '  (unchanged; pass -Handoff true|false to set it)')
    }

    # 2) force the re-bind: remove our component, let it unbind, then add it back.
    $cur = (Sh 'shell settings get secure enabled_accessibility_services').Trim()
    if ($cur -eq 'null') { $cur = '' }
    $rest = (($cur -split ':') | Where-Object { $_ -and $_ -ne $comp }) -join ':'
    if ($rest) {
        Write-Host ('  unbind -> ' + (Sh "shell settings put secure enabled_accessibility_services $rest"))
    } else {
        Write-Host ('  unbind -> ' + (Sh 'shell settings delete secure enabled_accessibility_services'))
    }
    Start-Sleep -Seconds 2
    Write-Host ('  rebind -> ' + (Sh "shell settings put secure enabled_accessibility_services $comp"))
    Write-Host (Sh 'shell settings put secure accessibility_enabled 1')
    Start-Sleep -Seconds 6
    Write-Host ('  now    = ' + (Sh 'shell settings get secure enabled_accessibility_services'))

    # 3) PROVE it re-bound, and that the new bind read the gate we asked for. A stale log file name
    #    here is the whole reason the 17:1x verification run was wasted: the tap ran a full chain
    #    against handoff=false while the file said true.
    $ls2    = Sh "shell ls -t $phone"
    $newest2 = (($ls2 -split "`n") | Where-Object { $_ -match 'spike_.*\.log$' } | Select-Object -First 1)
    $name2  = if ($newest2) { $newest2.Trim() } else { '' }
    if ($name2 -and $name2 -ne $name) {
        Write-Host ''
        Write-Host "  NEW BIND: $name2"
        $bind = @((Sh "shell grep -h SERVICE_CONNECTED $phone/$name2") -split "`n") | Select-Object -Last 1
        Write-Host ('  ' + $bind)
        if ($Handoff -eq 'true' -or $Handoff -eq 'false') {
            if ($bind -match ("handoff=" + $Handoff)) {
                Write-Host "  OK: the live process read handoff=$Handoff — the gate is IN EFFECT."
            } else {
                Write-Host "  !! WARNING: the new bind does not report handoff=$Handoff — do NOT trust this run."
            }
        }
    } else {
        Write-Host ''
        Write-Host "  !! NO NEW BIND (still $name2): the service did not re-bind, so the gate was NOT re-read."
        Write-Host '     Fix by hand: Settings > Accessibility > DOWNI Fetcher Spike OFF, then ON, then check'
        Write-Host '     for a new SERVICE_CONNECTED line before tapping the bubble.'
    }
    Write-Host ''
    Write-Host 'Armed. Now open Instagram/TikTok, watch a video, and leave it for 5+ minutes.'
}

if ($Pull) {
    Head 'PULL'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    Write-Host (Sh "pull $phone `"$out`"")
    Write-Host "into $out"
}

Write-Host ''
Write-Host 'Decide by what is present:'
Write-Host '  SERVICE_UNBIND in the spike log      -> Android/a11y unbound us (H1 class)'
Write-Host '  BUBBLE_WINDOW_LOST in the spike log  -> ROM dropped the overlay, bubble rebuilt itself (H4)'
Write-Host '  log ends with no marker at all       -> hard kill; read CRASH BUFFER above (H2/H1)'
Write-Host '  am_kill … com.vivo.abe in KILL/PROC  -> confirmed vivo Application Behavior Engine kill (H1)'
Write-Host '  no FGS_START in the spike log        -> the foreground-service defence is not armed (see §6)'
