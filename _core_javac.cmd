@echo off
rem V3.2 Core — foreground-free javac check (writes both streams into one log).
set JAVA_HOME=%~dp0tools\jdk\jdk-21.0.12.1+1
set ANDROID_HOME=%~dp0android-sdk
set ANDROID_USER_HOME=%~dp0.android-home
call "%~dp0android\gradlew.bat" -p "%~dp0android" :app:compileDebugJavaWithJavac --console=plain > "%~dp0_core_javac.log" 2>&1
