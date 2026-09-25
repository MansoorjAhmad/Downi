@echo off
rem Phase A gate, step 3: drive every Core state + the 48/56/64 dp comparison, screenshot each.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\core_states.ps1" -Sizes > "%~dp0_core_states.log" 2>&1
echo === STATES DONE === >> "%~dp0_core_states.log"
