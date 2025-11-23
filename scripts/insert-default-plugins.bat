@echo off
REM =============================================================================
REM INSERT DEFAULT PLUGINS INTO MONGODB
REM =============================================================================
REM 
REM This script directly inserts the default plugins into MongoDB.
REM Faster and simpler than uploading through the API.
REM 
REM Prerequisites:
REM - MongoDB running on localhost:27017
REM - Node.js installed

echo ============================================================================
echo INSERT DEFAULT PLUGINS INTO MONGODB
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

REM Check if MongoDB driver is installed
if not exist "%~dp0node_modules\mongodb" (
    echo [93mInstalling MongoDB driver...[0m
    cd /d "%~dp0"
    call npm install mongodb
    if %ERRORLEVEL% NEQ 0 (
        echo [91mFailed to install MongoDB driver![0m
        pause
        exit /b 1
    )
)

REM Run the insert script
echo.
echo [96mInserting plugins into database...[0m
cd /d "%~dp0"
node insert-default-plugins.js

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [92m✓ Plugins inserted successfully![0m
    echo.
    echo You can now see them in the Plugin Marketplace!
) else (
    echo.
    echo [91m✗ Failed to insert plugins![0m
    echo Please check if MongoDB is running.
)

echo.
pause
