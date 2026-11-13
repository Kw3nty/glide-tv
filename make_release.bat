@echo off
rem Glide TV: bump version, tag and publish a release
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\windows\make_release.ps1"
echo.
pause
