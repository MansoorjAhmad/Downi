# safe_shot.ps1 - binary-safe device screenshot capture + image verification.
#
# WHY THIS EXISTS (2026-09-24 incident): PowerShell 5.1 `>` / Out-File rewrites native
# stdout as UTF-16 text. `adb exec-out screencap -p > shot.png` produced a ~2x-size
# UTF-16 file (starts FF FE, not 89 50 4E 47) that still passed a byte-size check.
# Attaching one to the model killed the session twice with
#   "Upstream error from DeepInfra: Failed to load image: cannot identify image file"
# and every later turn in that session failed (the bad image stays in context).
#
# RULE: capture with this script (device-side screencap + `adb pull` = byte-exact),
# and verify any image with -Check before read_files.
#
# Usage:
#   tools\safe_shot.ps1 -Out test_out\shot1.png            # capture + verify (PNG)
#   tools\safe_shot.ps1 -Out test_out\shot1.png -Jpg       # capture + convert to .jpg
#   tools\safe_shot.ps1 -Check test_out\shot1.png          # verify an existing file
#   (optionally -Serial <serial>; default = whatever adb reports as connected)
#
# Exit codes: 0 = OK (PNG/JPEG verified) - 2 = capture/verify failed - 1 = usage.
param(
    [string]$Out,
    [switch]$Jpg,
    [string]$Check,
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $repo 'android-sdk\platform-tools\adb.exe'

function Get-ImageKind([string]$path) {
    if (-not (Test-Path $path)) { return 'MISSING' }
    $b = [IO.File]::ReadAllBytes($path)
    if ($b.Length -lt 8) { return 'TOO-SMALL' }
    if ($b[0] -eq 0x89 -and $b[1] -eq 0x50 -and $b[2] -eq 0x4E -and $b[3] -eq 0x47) { return 'PNG' }
    if ($b[0] -eq 0xFF -and $b[1] -eq 0xD8 -and $b[2] -eq 0xFF) { return 'JPEG' }
    if ($b[0] -eq 0xFF -and $b[1] -eq 0xFE) { return 'UTF16-TEXT-BROKEN' }
    return 'UNKNOWN'
}

if ($Check) {
    $kind = Get-ImageKind $Check
    Write-Output ("CHECK " + $Check + " -> " + $kind)
    if ($kind -eq 'PNG' -or $kind -eq 'JPEG') { exit 0 }
    exit 2
}

if (-not $Out) {
    Write-Output 'usage: tools\safe_shot.ps1 -Out <file.png> [-Jpg] [-Serial <serial>]  |  -Check <file>'
    exit 1
}

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

# device must be online before anything else
$devices = (& $adb @($adbArgs + @('get-state')) 2>$null | Out-String).Trim()
if ($devices -ne 'device') {
    Write-Output ("CAPTURE FAILED: adb get-state = '" + $devices + "' (device offline? cable?)")
    exit 2
}

# capture on the device, then pull the raw bytes (adb pull is byte-exact)
$remote = '/sdcard/downi_safe_shot.png'
& $adb @($adbArgs + @('shell', 'screencap', '-p', $remote)) | Out-Null
& $adb @($adbArgs + @('pull', $remote, $Out)) | Out-Null
& $adb @($adbArgs + @('shell', 'rm', '-f', $remote)) | Out-Null

$kind = Get-ImageKind $Out
if ($kind -ne 'PNG') {
    Remove-Item $Out -Force -ErrorAction SilentlyContinue
    Write-Output ("CAPTURE FAILED: " + $Out + " was " + $kind + " (expected PNG) - deleted, do NOT attach it")
    exit 2
}
Write-Output ("OK " + $Out + " (" + (Get-Item $Out).Length + " bytes, PNG) - safe to attach")

if ($Jpg) {
    Add-Type -AssemblyName System.Drawing
    $jpgOut = [IO.Path]::ChangeExtension($Out, '.jpg')
    $img = [System.Drawing.Image]::FromFile($Out)
    try { $img.Save($jpgOut, [System.Drawing.Imaging.ImageFormat]::Jpeg) } finally { $img.Dispose() }
    $jkind = Get-ImageKind $jpgOut
    if ($jkind -ne 'JPEG') {
        Write-Output ("CONVERT FAILED: " + $jpgOut + " -> " + $jkind)
        exit 2
    }
    Write-Output ("OK " + $jpgOut + " (" + (Get-Item $jpgOut).Length + " bytes, JPEG) - safe to attach")
}

exit 0
