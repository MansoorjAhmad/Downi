# Wait for the phone on USB, switch it to TCP/IP, then connect over Wi-Fi.
# Built for a flaky cable: acts the instant the device appears, so even a
# 2-second USB connection is enough. After this, unplug the cable freely.
$ErrorActionPreference = 'SilentlyContinue'
$adb = 'C:\Users\Manso\Documents\Codex\2026-09-06\bro-i-have-started-working-on\mobile-app\android-sdk\platform-tools\adb.exe'
$deadline = (Get-Date).AddMinutes(4)

Write-Host 'Waiting for the phone on USB (plug it in now)...'
while ((Get-Date) -lt $deadline) {
    $onUsb = & $adb devices 2>$null | Select-String -Pattern "`tdevice$"
    if ($onUsb) {
        Write-Host 'Device on USB — switching to TCP/IP mode (port 5555)...'
        & $adb tcpip 5555 2>$null | Out-Host
        Start-Sleep -Seconds 2
        $ipOut = & $adb shell "ip -f inet addr show wlan0" 2>$null | Out-String
        $ip = ([regex]'inet (\d+\.\d+\.\d+\.\d+)').Match($ipOut).Groups[1].Value
        Write-Host "Phone Wi-Fi IP: $ip"
        if ($ip) {
            & $adb connect "${ip}:5555" 2>$null | Out-Host
            Start-Sleep -Seconds 2
            $wifi = & $adb devices 2>$null | Select-String -Pattern ([regex]::Escape("${ip}:5555") + "`tdevice$")
            if ($wifi) {
                Write-Host ''
                Write-Host "=== WIRELESS OK ===  You can unplug the cable now. ($ip:5555)"
                exit 0
            }
            Write-Host 'USB switched, but the Wi-Fi connect did not land yet — retrying...'
        } else {
            Write-Host 'No Wi-Fi IP on wlan0 — is the phone on Wi-Fi (not just mobile data)?'
        }
    }
    Start-Sleep -Milliseconds 900
}
Write-Host 'Timed out. Re-run this script and plug the cable in.'
exit 1
