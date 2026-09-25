@echo off
rem V3.2 Core — pure-logic unit tests (CoreLook / CoreMotion / CoreStates).
set JAVA_HOME=%~dp0tools\jdk\jdk-21.0.12.1+1
set ANDROID_HOME=%~dp0android-sdk
set ANDROID_USER_HOME=%~dp0.android-home
call "%~dp0android\gradlew.bat" -p "%~dp0android" :app:testDebugUnitTest --console=plain > "%~dp0_core_test.log" 2>&1
