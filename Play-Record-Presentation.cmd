@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\launch-present-profile.ps1"
if errorlevel 1 pause
