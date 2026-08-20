@echo off
REM Local Development Build Script for OntoCode
REM This script builds all services from local source code

echo ========================================
echo OntoCode Local Development Build
echo ========================================
echo.

echo Building all services from local source...
echo This may take 10-15 minutes on first run...
echo.

docker compose -f docker-compose.dev.yml build %*

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo Build completed successfully!
    echo ========================================
    echo.
    echo To start all services, run:
    echo   docker compose -f docker-compose.dev.yml up -d
    echo.
    echo To rebuild and restart:
    echo   docker compose -f docker-compose.dev.yml up -d --build
    echo.
    echo To view logs:
    echo   docker compose -f docker-compose.dev.yml logs -f
    echo.
) else (
    echo.
    echo ========================================
    echo Build failed! Check errors above.
    echo ========================================
    echo.
)
