# _J_export.ps1 â€” the black box exporter, made boring and verifiable.
#
# Two traps this avoids, both hit for real on 2026-09-28:
#   1. NEVER hardcode the Export button: the Settings card grows a line as grabs accumulate
#      ("today: N grabs"), which moved Export from yâ‰ˆ1871 to yâ‰ˆ1796 in one evening. Ask the UI.
#   2. A stale file reads exactly like a fresh one. So this compares the file's mtime before and
#      after and REFUSES to call the result a reading unless it advanced.
param([string]$Tag = 'box')
$ErrorActionPreference = 'Continue'
$repo = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$adb  = Join-Path $repo 'android-sdk\platform-tools\adb.exe'
$pkg  = 'com.omnidownloader.app'
$phone = "/sdcard/Android/data/$pkg/files/fetch-spike"
$dir  = 'C:\Users\Manso\AppData\Local\Cline\_J'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function Sh($c) { (cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul')) -join "`n" }
# NOT named `CD`: PowerShell's built-in Set-Location ALIAS shadows a function of that name, so every
# device command silently became a failed Set-Location â€” which is exactly what produced the two
# "NO CORE over IG (0z-6?)" rounds earlier tonight. Aliases outrank functions in this shell.
function Dev($c) { cmd /c ('"' + $adb + '" shell ' + $c + ' 2>nul') | Out-Null }

$before = (Sh ('ls -l ' + $phone + '/blackbox.txt')).Trim()
Dev 'am start -n com.omnidownloader.app/.MainActivity'
Start-Sleep -Seconds 2
Dev 'input tap 898 2206'                       # Settings tab
Start-Sleep -Milliseconds 900

$ex = $null; $ey = $null
for ($a = 1; $a -le 3; $a++) {
    $dump = Join-Path $dir ($Tag + '_ui.xml')
    $dev  = '/sdcard/_J_d.xml'
    Remove-Item $dump -ErrorAction SilentlyContinue
    # Freshness first, for the same reason the TikTok rig needed it: `uiautomator dump` prints its own
    # message and writes NOTHING when it fails, and the `pull` after a failed dump silently fetches
    # the previous export's tree - which still contains an "Export" node, so a stale coordinate would
    # be tapped and it would look exactly like a real export. Delete the file, read the message,
    # refuse anything not freshly written (2026-09-29, _L\probe3/4/5.txt).
    Dev ('rm -f ' + $dev)
    $o = Sh ('timeout 25 uiautomator dump ' + $dev)
    if ($o -notmatch 'dumped to') {
        "EXPORT attempt $a : dump FAILED (not a UI verdict, the tree was never written): " + ($o.Trim() -replace "`n", ' ')
        Start-Sleep -Seconds 2
        continue
    }
    cmd /c ('"' + $adb + '" pull ' + $dev + ' "' + $dump + '" 2>nul') | Out-Null
    if (-not (Test-Path $dump)) { Start-Sleep -Seconds 1; continue }
    $x = [xml](Get-Content $dump)
    $n = $x.SelectNodes('//*[@text="Export"]') | Select-Object -First 1
    if ($n) {
        $c = [regex]::Matches($n.'bounds', '\d+') | ForEach-Object { [int]$_.Value }
        $ex = [int](($c[0] + $c[2]) / 2); $ey = [int](($c[1] + $c[3]) / 2)
        break
    }
    Start-Sleep -Seconds 1
}
if ($ex -eq $null) { "EXPORT_FAIL Export button not found"; exit 1 }

Dev ('input tap ' + $ex + ' ' + $ey)
Start-Sleep -Seconds 2
$after = (Sh ('ls -l ' + $phone + '/blackbox.txt')).Trim()
$body  = (Sh ('cat ' + $phone + '/blackbox.txt'))
$out   = Join-Path $dir ($Tag + '_box.txt')
$body | Out-File -FilePath $out -Encoding utf8

$advanced = ($before -ne $after)
"export_tap=$ex,$ey"
"mtime_before=$before"
"mtime_after =$after"
"ADVANCED=$advanced"
"box_lines=" + (@($body -split "`n").Count)
"box_file=$out"
$last = ($body -split "`n" | Where-Object { $_ } | Select-Object -Last 1)
if ($last) { "last_event=" + $last.Trim().Substring(0,[Math]::Min(90,$last.Trim().Length)) }
