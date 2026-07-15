@echo off
setlocal
:: Delegate to unified CLI (passes all args through).
:: Examples:
::   build-desktop-portable.bat --full --portable
::   build-desktop-portable.bat --web --portable
::   build-desktop-portable.bat --java --web --resources --portable
cd /d "%~dp0"
node scripts\build-desktop.js %*
exit /b %ERRORLEVEL%
