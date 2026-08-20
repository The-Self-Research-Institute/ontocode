@echo off
REM Setup Windows Task Scheduler to run query cleanup every 15 minutes

echo ╔════════════════════════════════════════════════════╗
echo ║   Setup Automatic Query Cleanup (Every 15 min)    ║
echo ╚════════════════════════════════════════════════════╝
echo.

REM Get current directory
set SCRIPT_DIR=%~dp0
set SCRIPT_PATH=%SCRIPT_DIR%cleanup-long-queries.js

echo Installing scheduled task...
echo.

REM Create scheduled task to run every 15 minutes
schtasks /Create /TN "OntoCode_QueryCleanup" /TR "node \"%SCRIPT_PATH%\"" /SC MINUTE /MO 15 /F

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ╔════════════════════════════════════════════════════╗
    echo ║   ✓ Scheduled Task Created Successfully           ║
    echo ╚════════════════════════════════════════════════════╝
    echo.
    echo Task Details:
    echo   Name: OntoCode_QueryCleanup
    echo   Runs: Every 15 minutes
    echo   Script: %SCRIPT_PATH%
    echo.
    echo To manage this task:
    echo   - View: taskschd.msc
    echo   - Disable: schtasks /Change /TN "OntoCode_QueryCleanup" /DISABLE
    echo   - Enable: schtasks /Change /TN "OntoCode_QueryCleanup" /ENABLE
    echo   - Delete: schtasks /Delete /TN "OntoCode_QueryCleanup" /F
    echo.
) else (
    echo.
    echo ╔════════════════════════════════════════════════════╗
    echo ║   ✗ Failed to Create Scheduled Task               ║
    echo ╚════════════════════════════════════════════════════╝
    echo.
    echo Please run this script as Administrator
    echo.
)

pause
