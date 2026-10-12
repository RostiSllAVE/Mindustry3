@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-lab.ps1" -Windowed
if errorlevel 1 pause
