@echo off
REM =============================================================================
REM RUN PLUGIN INITIALIZATION IN DOCKER
REM =============================================================================
REM
REM This script runs the plugin initialization service in Docker.
REM It inserts default plugins into MongoDB.
REM
REM Prerequisites:
REM - Docker and Docker Compose installed
REM - Main services running (mongo must be healthy)

echo ============================================================================
echo RUN PLUGIN INITIALIZATION
echo ============================================================================
echo.

REM Check if Docker is running
docker info >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [91mError: Docker is not running![0m
    echo Please start Docker Desktop first.
    pause
    exit /b 1
)

REM Run the plugin initialization service
echo [96mStarting plugin initialization service...[0m
echo.

docker compose -f docker-compose.yml run --rm plugin-init

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [92m✓ Plugins initialized successfully![0m
    echo You can now see them in the Plugin Marketplace!
) else (
    echo.
    echo [91m✗ Plugin initialization failed![0m
    echo Make sure MongoDB is running: docker compose -f docker-compose.yml up mongo
)

echo.
pause
