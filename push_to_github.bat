@echo off
rem Glide TV: push the project to GitHub
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\windows\push_to_github.ps1"
echo.
pause
