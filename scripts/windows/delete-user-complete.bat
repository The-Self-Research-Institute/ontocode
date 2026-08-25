@echo off
setlocal

if "%~1"=="" (
    echo Usage:
    echo   scripts\delete-user-complete.bat user@example.com
    echo.
    echo Dry-run is the default. To delete permanently:
    echo   scripts\delete-user-complete.bat user@example.com --execute --yes
    echo.
    exit /b 1
)

node "%~dp0delete-user-complete.js" %*
exit /b %ERRORLEVEL%
