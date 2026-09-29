# _L_fp_test.ps1 - does the rig's OWN Fingerprint() still match this TikTok version?
#
# The function is lifted straight out of _L_tiktok_control.ps1 (lines 53-70) and run against real
# trees, so this tests the code that will actually run - not a retyped copy of it. The fossil tree
# (before_1.xml, the app's own Diagnostics screen that the unguarded pull handed back) is included
# on purpose: a fingerprint must report NULL for it, because that tree is exactly what caused four
# bogus "TikTok is not showing a feed video" SKIPs.
$ErrorActionPreference = 'Continue'
$cline = 'C:\Users\Manso\AppData\Local\Cline'
$rig   = Join-Path $cline '_L_tiktok_control.ps1'
$dir   = Join-Path $cline '_L'

$lines = Get-Content $rig
$fn = ($lines[52..69]) -join "`n"
Invoke-Expression $fn
if (-not (Get-Command Fingerprint -ErrorAction SilentlyContinue)) { Write-Output 'FATAL: Fingerprint did not load'; exit 1 }

$files = Get-ChildItem (Join-Path $dir '*.xml') | Where-Object { $_.Name -match '^(p3_|p4_|_L_probe|_diag|before_|after_)' } | Sort-Object Name
foreach ($f in $files) {
    try { $x = [xml](Get-Content $f.FullName) } catch { Write-Output ($f.Name.PadRight(26) + ' UNPARSEABLE'); continue }
    $pkgs = @($x.SelectNodes('//*[@package]') | ForEach-Object { $_.package } | Sort-Object -Unique) -join ','
    $fp = Fingerprint $x
    $verdict = $(if ($fp) { 'FP: ' + $fp } else { 'NULL (no title/desc/sound/like/share/read keys)' })
    Write-Output ($f.Name.PadRight(26) + ' pkg=' + $pkgs.PadRight(28) + ' ' + $verdict)
}
