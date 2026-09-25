@echo off
rem V3.2 Core - CLEAN debug build. Use when an APK is missing or gradle claims success without
rem packaging (seen 2026-09-25 after build intermediates were deleted by hand: assembleDebug
rem reported BUILD SUCCESSFUL while outputs\apk\debug stayed empty, and the stale APK got signed
rem and installed). Same env as _core_build.cmd, plus `clean`.
set JAVA_HOME=%~dp0tools\jdk\jdk-21.0.12.1+1
set ANDROID_HOME=%~dp0android-sdk
set ANDROID_USER_HOME=%~dp0.android-home
call "%~dp0android\gradlew.bat" -p "%~dp0android" clean :app:assembleDebug --console=plain > "%~dp0_core_build.log" 2>&1
set APK=%~dp0android\app\build\outputs\apk\debug\app-debug.apk
powershell -NoProfile -Command "$apk = Get-Item '%APK%' -ErrorAction SilentlyContinue; if (-not $apk) { Write-Host '=== FRESHNESS: NO APK - build produced nothing ===' } else { $src = Get-ChildItem -Recurse '%~dp0android\app\src' -Include *.java,*.xml | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($src.LastWriteTime -gt $apk.LastWriteTime) { Write-Host ('=== FRESHNESS: STALE APK - do not sign/install === apk=' + $apk.LastWriteTime.ToString('HH:mm:ss') + ' newest_src=' + $src.LastWriteTime.ToString('HH:mm:ss') + ' (' + $src.Name + ')') } else { Write-Host ('=== FRESHNESS: OK apk=' + $apk.LastWriteTime.ToString('HH:mm:ss') + ' ===') } }" >> "%~dp0_core_build.log" 2>&1
echo === BUILD DONE === >> "%~dp0_core_build.log"
