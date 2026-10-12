@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\launch-live-camera-profile.ps1" -InstalledBaseline
if errorlevel 1 pause
