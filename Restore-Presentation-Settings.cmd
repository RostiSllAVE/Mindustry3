@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ensure-presentation-mode.ps1" -Mode restore
pause
