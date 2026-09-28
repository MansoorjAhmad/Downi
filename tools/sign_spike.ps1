# Re-signs the debug APK with the PROD keystore - the v3.2 spike installs expect it.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\sign_spike.ps1
#
# Why: the phone already carries a prod-signed build (`CN=Manso, O=OmniDownloader`), so a
# debug-signed APK cannot install over it — and uninstalling would wipe the prefs the spike
# depends on. Result: android\app\build\outputs\apk\debug\app-spike-signed.apk, verified.
$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$props = Join-Path $root 'android\keystore.properties'
if (-not (Test-Path $props)) { Write-Host "missing $props"; exit 1 }

$kv = @{}
Get-Content $props | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.+?)\s*$') { $kv[$matches[1]] = $matches[2] }
}

$storeRel = $kv['storeFile']
$candidates = @()
if ([IO.Path]::IsPathRooted($storeRel)) {
    $candidates += ($storeRel -replace '/', '\')     # the prod keystore lives outside the repo
} else {
    $candidates += (Join-Path $root "android\app\$storeRel")
    $candidates += (Join-Path $root "android\$storeRel")
    $candidates += (Join-Path $root $storeRel)
}
$ks = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $ks) { Write-Host "keystore not found for storeFile=$storeRel"; exit 1 }

$apksigner = Join-Path $root 'android-sdk\build-tools\34.0.0\apksigner.bat'
if (-not (Test-Path $apksigner)) { Write-Host "apksigner not found at $apksigner"; exit 1 }

$dir = Join-Path $root 'android\app\build\outputs\apk\debug'
$in  = Join-Path $dir 'app-debug.apk'
$out = Join-Path $dir 'app-spike-signed.apk'
if (-not (Test-Path $in)) { Write-Host "build first: $in is missing"; exit 1 }

& $apksigner sign --ks $ks --ks-key-alias $kv['keyAlias'] `
    --ks-pass ("pass:" + $kv['storePassword']) --key-pass ("pass:" + $kv['keyPassword']) `
    --out $out $in
if ($LASTEXITCODE -ne 0) { Write-Host 'sign failed'; exit 1 }

Write-Host ''
Write-Host 'signed -> ' $out
& $apksigner verify --print-certs $out | Select-String 'Subject|SHA-256'
Write-Host ''
Write-Host 'Expect: CN=Manso, O=OmniDownloader / SHA-256 431131731d7b26dadd6dc6ffa3ef337853f30a63decbb863bcec2a61bb0785e5'
