param(
    [string]$At = '',          # x,y of the Core's window (default: read from the device's prefs)
    [int]$SizeDp = 0,          # default: read from the device's prefs
    [double]$Density = 2.75,   # vivo V2058 = 440 dpi
    [int]$Pad = 44,            # roomy margin around the disc, in px
    [string]$Dir = 'test_out\core_visual',  # which shot folder to build the strips from
    [bool]$Fixed = $true       # hand the audit --fixed. The V3.3 baked art has no rim stroke for
                               # fit_disc to lock onto, so without it every state reports "no rim at
                               # all" and the ring reads 0% - which is why the M2 passes' _gate_kA3/
                               # kA4 logs show 16 and 5 mismatches on shots that are plainly correct.
                               # `-Fixed:$false` reproduces the v3.1/v3.2 procedural numbers.
)
Add-Type -AssemblyName System.Drawing
$repo = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app'
$src = Join-Path $repo $Dir
$dst = $src

# The crop must follow where the Core actually is: it is placed with `at x y` (tools\core_mark_gate.ps1)
# and remembered in `downi_fetcher` prefs, so a hardcoded box silently frames empty wallpaper the
# moment the position changes - exactly what happened when the mark gate moved the Core to (500,1000)
# while this script still looked at the old default (800,1424).
if ($At -eq '' -or $SizeDp -le 0) {
    $adb = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app\android-sdk\platform-tools\adb.exe'
    $xml = (& $adb shell run-as com.omnidownloader.app cat /data/data/com.omnidownloader.app/shared_prefs/downi_fetcher.xml 2>&1) -join "`n"
    if ($At -eq '') {
        $mx = [regex]::Match($xml, 'name="core_x" value="(\d+)"')
        $my = [regex]::Match($xml, 'name="core_y" value="(\d+)"')
        $At = if ($mx.Success -and $my.Success) { $mx.Groups[1].Value + ',' + $my.Groups[1].Value } else { '500,1000' }
    }
    if ($SizeDp -le 0) {
        $ms = [regex]::Match($xml, 'name="core_size_dp" value="(\d+)"')
        $SizeDp = if ($ms.Success) { [int]$ms.Groups[1].Value } else { 64 }
    }
    Write-Output ("core at " + $At + " (" + $SizeDp + " dp, from " + $(if ($xml -match 'core_x') { 'device prefs' } else { 'fallback' }) + ")")
}
$xy = $At.Split(',')
$cx = [int]$xy[0]
$cy = [int]$xy[1]
$side = [int][Math]::Round($SizeDp * $Density) + 2 * $Pad
$box = New-Object System.Drawing.Rectangle(($cx - $Pad), ($cy - $Pad), $side, $side)
$font = New-Object System.Drawing.Font('Arial', 13, [System.Drawing.FontStyle]::Bold)
$brush = [System.Drawing.Brushes]::White
$bg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 10, 14, 22))

function Tile($name, $size) {
    $f = Join-Path $src ($name + '.png')
    if (-not (Test-Path $f)) { return $null }
    $img = [System.Drawing.Image]::FromFile($f)
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $size, $size)), $box, 'Pixel')
    $g.Dispose(); $img.Dispose()
    return $bmp
}

function Strip($names, $labels, $tile, $file, $title) {
    $w = $tile * $names.Count
    $h = $tile + 46
    $out = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($out)
    $g.Clear([System.Drawing.Color]::FromArgb(255, 8, 12, 18))
    $g.DrawString($title, $font, $brush, 8, 4)
    for ($i = 0; $i -lt $names.Count; $i++) {
        $b = Tile $names[$i] $tile
        if ($b -ne $null) { $g.DrawImage($b, ($i * $tile), 30); $b.Dispose() }
        $g.DrawString($labels[$i], $font, $brush, ($i * $tile + 8), ($tile + 30))
    }
    $g.Dispose()
    $out.Save((Join-Path $dst $file), [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $out.Dispose()
    Write-Output ('wrote ' + $file)
}

Strip @('core_size_48dp', 'core_size_56dp', 'core_size_64dp') @('48 dp', '56 dp', '64 dp') 300 '_review_sizes.jpg' 'K-A2  mark + size comparison (design-sheet-2 mark, never the app icon)'
Strip @('core_progress_000', 'core_progress_025', 'core_progress_050', 'core_progress_075', 'core_progress_100') @('0%', '25%', '50%', '75%', '100%') 240 '_review_progress.jpg' 'K-A4  progress on the energy perimeter only - no percent text anywhere'

$states = @('core_state_idle', 'core_state_wake_end', 'core_state_detected', 'core_state_pressed_mid',
            'core_state_dragging', 'core_state_snapped_mid', 'core_state_paused', 'core_state_resuming_mid',
            'core_state_completing_mid', 'core_state_complete', 'core_state_failed_end', 'core_zz_hidden')
$labels = @('idle', 'wake end', 'detected', 'pressed (mid)', 'dragging', 'snapped (mid)', 'paused',
            'resuming (mid)', 'completing (mid)', 'complete', 'failed (settled)', 'hidden')
$tile = 240
$cols = 4
$rows = 3
$out = New-Object System.Drawing.Bitmap(($cols * $tile), ($rows * ($tile + 30) + 30))
$g = [System.Drawing.Graphics]::FromImage($out)
$g.Clear([System.Drawing.Color]::FromArgb(255, 8, 12, 18))
$g.DrawString('K-A3 / K-A4  Core state spectrum (shots straight from the device)', $font, $brush, 8, 4)
for ($i = 0; $i -lt $states.Count; $i++) {
    $col = $i % $cols
    $row = [Math]::Floor($i / $cols)
    $b = Tile $states[$i] $tile
    if ($b -ne $null) { $g.DrawImage($b, ($col * $tile), (30 + $row * ($tile + 30))); $b.Dispose() }
    $g.DrawString($labels[$i], $font, $brush, ($col * $tile + 8), (30 + $row * ($tile + 30) + $tile + 4))
}
$g.Dispose()
$out.Save((Join-Path $dst '_review_states.jpg'), [System.Drawing.Imaging.ImageFormat]::Jpeg)
$out.Dispose()
Write-Output 'wrote _review_states.jpg'

# The strip shows the states in order; these numbers say what each one actually painted (rim colour,
# bars, and how much of the energy perimeter is lit). They travel together on purpose: a strip alone
# hides a wrong rim (a rose PAUSED reads as "a mood") and a number alone hides nothing much, but a
# strip plus its numbers is what cell K-A3/K-A4 is judged on. tools/core_state_audit.py explains the
# bands, and `profile`/`span` print the raw radial profile and the arc's degrees if a number is
# ever questioned.
$py = 'python'
$root = Split-Path -Parent $PSScriptRoot
$fx = @()
if ($Fixed) { $fx = @('--fixed') }
Push-Location $root
& $py 'tools\core_state_audit.py' dir $Dir --at $At --glob 'core_state_*.png' @fx 2>&1 |
    Tee-Object -FilePath (Join-Path $Dir '_gate_kA3_audit.log') | Select-String -Pattern '^core_|rim |mark |bars |ring |state |note |VERDICT'
& $py 'tools\core_state_audit.py' dir $Dir --at $At --glob 'core_progress_*.png' --want-ring @fx 2>&1 |
    Tee-Object -FilePath (Join-Path $Dir '_gate_kA4_audit.log') | Select-String -Pattern '^core_|ring |K-A4|state |VERDICT'
& $py 'tools\core_state_audit.py' dir $Dir --at $At --glob 'core_zz_*.png' @fx 2>&1 |
    Select-String -Pattern '^core_zz|fit |state |VERDICT'
Pop-Location
