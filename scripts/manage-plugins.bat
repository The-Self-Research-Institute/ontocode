@echo off
REM =============================================================================
REM PLUGIN MANAGER - Build and Install OntoCode Plugins
REM =============================================================================
REM 
REM This script manages all plugin operations:
REM - Build plugins
REM - Insert metadata into MongoDB
REM - Upload bundles to GridFS
REM 
REM Prerequisites:
REM - MongoDB running on localhost:27017
REM - Node.js installed
REM
REM Usage:
REM   manage-plugins.bat [command] [options]
REM 
REM Commands:
REM   all       - Build, insert metadata, and upload bundles (default)
REM   build     - Build all plugins
REM   install   - Insert metadata and upload bundles (no build)
REM   list      - List installed plugins
REM   clean     - Remove all plugins from database
REM 
REM Options:
REM   --plugin [id]   - Process only specified plugin
REM   --skip-build    - Skip building plugins

echo.
echo ============================================================================
echo           OntoCode Plugin Manager
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
    echo [93mInstalling dependencies...[0m
    cd /d "%~dp0"
    call npm install mongodb
    echo.
)

REM Run the plugin manager
cd /d "%~dp0"
node manage-plugins.js %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [91mPlugin manager failed with error code %ERRORLEVEL%[0m
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [92mDone![0m
pause
