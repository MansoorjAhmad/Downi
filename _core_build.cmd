@echo off
rem V3.2 Core - full debug build (compile + package), both streams into one log.
set JAVA_HOME=%~dp0tools\jdk\jdk-21.0.12.1+1
set ANDROID_HOME=%~dp0android-sdk
set ANDROID_USER_HOME=%~dp0.android-home
call "%~dp0android\gradlew.bat" -p "%~dp0android" :app:assembleDebug --console=plain > "%~dp0_core_build.log" 2>&1
echo === BUILD DONE === >> "%~dp0_core_build.log"
