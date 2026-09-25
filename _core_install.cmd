@echo off
rem Phase A gate, step 1: install the prod-signed spike APK over the existing app (-r keeps prefs).
set ADB=%~dp0android-sdk\platform-tools\adb.exe
"%ADB%" install -r "%~dp0android\app\build\outputs\apk\debug\app-spike-signed.apk" > "%~dp0_core_install.log" 2>&1
echo === INSTALL DONE === >> "%~dp0_core_install.log"
