@echo off
rem V3.2 Core - full debug build (compile + package), both streams into one log.
set JAVA_HOME=%~dp0tools\jdk\jdk-21.0.12.1+1
set ANDROID_HOME=%~dp0android-sdk
set ANDROID_USER_HOME=%~dp0.android-home
call "%~dp0android\gradlew.bat" -p "%~dp0android" :app:assembleDebug --console=plain > "%~dp0_core_build.log" 2>&1
rem Freshness check (added 2026-09-25 after an hour was lost to this): gradle can print
rem "BUILD SUCCESSFUL" without repackaging the APK, and sign_spike.ps1 will then happily sign the
rem OLD apk — so the phone runs old code while every log says the new code was installed. The APK
rem must be newer than the newest file under app\src or this build is worthless.
set APK=%~dp0android\app\build\outputs\apk\debug\app-debug.apk
powershell -NoProfile -Command "$apk = Get-Item '%APK%' -ErrorAction SilentlyContinue; if (-not $apk) { Write-Host '=== FRESHNESS: NO APK - build produced nothing ==='; exit 5 }; $src = Get-ChildItem -Recurse '%~dp0android\app\src' -Include *.java,*.xml | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($src.LastWriteTime -gt $apk.LastWriteTime) { Write-Host ('=== FRESHNESS: STALE APK - do not sign/install === apk=' + $apk.LastWriteTime.ToString('HH:mm:ss') + ' newest_src=' + $src.LastWriteTime.ToString('HH:mm:ss') + ' (' + $src.Name + ')') } else { Write-Host ('=== FRESHNESS: OK apk=' + $apk.LastWriteTime.ToString('HH:mm:ss') + ' ===') }" >> "%~dp0_core_build.log" 2>&1
echo === BUILD DONE === >> "%~dp0_core_build.log"
