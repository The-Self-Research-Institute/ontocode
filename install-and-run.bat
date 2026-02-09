@echo off
REM ========================================
REM OntoCode - One-Click Installation
REM ========================================
REM Pulls pre-built images from Docker Hub (sindhujacoretopia)
REM and starts all services. No build tools required.
REM ========================================

setlocal enabledelayedexpansion

set REGISTRY=sindhujacoretopia
set VERSION=latest

echo.
echo ========================================
echo   OntoCode One-Click Installation
echo   Registry: %REGISTRY%
echo ========================================
echo.

REM [1/5] Check Docker
echo [1/5] Checking Docker...
docker --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not installed.
    echo Install from: https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)
docker ps >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop.
    pause
    exit /b 1
)
echo [OK] Docker is running

REM [2/5] Prepare directories
echo.
echo [2/5] Preparing workspace...
if not exist "data\projects" mkdir data\projects >nul 2>&1
if not exist ".env" (
    echo [INFO] Creating .env from defaults...
    if exist ".env.example" copy .env.example .env >nul 2>&1
)
echo [OK] Workspace ready

REM [3/5] Pull pre-built images
echo.
echo [3/5] Pulling pre-built images from %REGISTRY%...
echo This may take a few minutes on first run...
echo.
for %%I in (ontocode-graphdb ontocode-auth ontocode-gateway ontocode-editor ontocode-swrl ontocode-plugin ontocode-plugin-init ontocode-vscode-web) do (
    echo   Pulling %REGISTRY%/%%I:%VERSION%...
    docker pull %REGISTRY%/%%I:%VERSION% >nul 2>&1
    if errorlevel 1 (
        echo   [WARN] Failed to pull %%I - will build locally
    ) else (
        echo   [OK] %%I
    )
)
echo.
echo [OK] Images ready

REM [4/5] Start services
echo.
echo [4/5] Starting all services...
docker compose down >nul 2>&1
set DOCKER_REGISTRY=%REGISTRY%
docker compose up -d
if errorlevel 1 (
    echo [ERROR] Failed to start services. Check errors above.
    pause
    exit /b 1
)
echo [OK] All services started

REM [5/5] Wait and open
echo.
echo [5/5] Waiting for services to be ready...
timeout /t 40 /nobreak >nul
echo [OK] Services initialized

echo.
echo ========================================
echo   OntoCode is running!
echo ========================================
echo.
echo   VS Code Web Editor:  http://localhost:3000
echo   API Gateway:         http://localhost:80
echo   GraphDB:             http://localhost:7200
echo   MongoDB:             mongodb://localhost:27017
echo.
echo   Stop:  docker compose down
echo   Logs:  docker compose logs -f
echo.
echo ========================================
echo Opening VS Code Web Editor...
timeout /t 3 /nobreak >nul
start http://localhost:3000
echo.
pause
