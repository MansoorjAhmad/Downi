@echo off
rem Phase A gate, step 2: re-arm the accessibility service (every install wipes it) + whitelist.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\fetch_diag.ps1" -Arm > "%~dp0_core_arm.log" 2>&1
echo === ARM DONE === >> "%~dp0_core_arm.log"
