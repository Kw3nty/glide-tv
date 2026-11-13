@echo off
rem Glide TV: create release signing key and GitHub secrets (once)
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\windows\setup_release_keys.ps1"
echo.
pause
