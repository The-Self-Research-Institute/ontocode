@echo off
REM =============================================================================
REM UPLOAD DEFAULT PLUGINS TO BACKEND
REM =============================================================================
REM 
REM This script uploads the default plugins to the plugin service backend.
REM 
REM Prerequisites:
REM - Plugin service running on localhost:8087
REM - MongoDB running
REM - Node.js installed

echo ============================================================================
echo UPLOAD DEFAULT PLUGINS TO BACKEND
echo ============================================================================
echo.

REM Check if Node.js is installed
where node >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [91mError: Node.js is not installed![0m
    echo Please install Node.js from https://nodejs.org/
    pause
    exit /b 1
)

REM Check if npm dependencies are installed
if not exist "%~dp0node_modules" (
    echo [93mInstalling dependencies...[0m
    cd /d "%~dp0"
    call npm install
    if %ERRORLEVEL% NEQ 0 (
        echo [91mFailed to install dependencies![0m
        pause
        exit /b 1
    )
)

REM Run the upload script
echo.
echo [96mRunning upload script...[0m
cd /d "%~dp0"
node upload-default-plugins.js

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [92m✓ Upload completed successfully![0m
) else (
    echo.
    echo [91m✗ Upload failed![0m
)

echo.
pause
