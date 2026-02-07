@echo off
REM ========================================
REM OntoCode - One-Click Installation
REM ========================================
REM This script will:
REM 1. Check prerequisites (Docker, Node.js)
REM 2. Build all Docker services
REM 3. Start the entire platform
REM 4. Build and launch the VS Code web extension
REM ========================================

setlocal enabledelayedexpansion

echo.
echo ========================================
echo   OntoCode One-Click Installation
echo ========================================
echo.

REM Check if Docker is running
echo [1/6] Checking Docker...
docker --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not installed or not in PATH
    echo Please install Docker Desktop from: https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)

docker ps >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running
    echo Please start Docker Desktop and try again
    pause
    exit /b 1
)
echo [OK] Docker is running

REM Ensure data directory exists
if not exist "data\projects" (
    mkdir data\projects >nul 2>&1
)

REM Check if Node.js is installed
echo.
echo [2/6] Checking Node.js...
set NODE_AVAILABLE=0
node --version >nul 2>&1
if errorlevel 1 (
    echo [WARNING] Node.js is not installed - using Docker-only mode
    echo The VS Code extension will run in a Docker container
    set NODE_AVAILABLE=0
) else (
    echo [OK] Node.js is installed - using local build mode
    set NODE_AVAILABLE=1
)

REM Stop any existing containers
echo.
echo [3/6] Cleaning up existing containers...
docker compose down -v >nul 2>&1
echo [OK] Cleanup complete

REM Build and start all Docker services
echo.
echo [4/6] Building and starting Docker services...
echo This may take several minutes on first run...
docker compose up -d --build
if errorlevel 1 (
    echo [ERROR] Failed to start Docker services
    echo Check the error messages above
    pause
    exit /b 1
)
echo [OK] Docker services started successfully

REM Wait for services to be ready
echo.
echo [5/6] Waiting for services to initialize...
timeout /t 30 /nobreak >nul
echo [OK] Services should be ready

REM Build and launch VS Code web extension
echo.
echo [6/6] Setting up VS Code Web Extension...

if %NODE_AVAILABLE%==1 (
    echo Using local Node.js build...
    cd ontology-vscode-extension

    REM Check if node_modules exists, install if not
    if not exist "node_modules\" (
        echo Installing extension dependencies...
        call npm install
        if errorlevel 1 (
            echo [ERROR] Failed to install dependencies
            cd ..
            pause
            exit /b 1
        )
    )

    echo Building web extension bundle...
    call npm run bundle:web
    if errorlevel 1 (
        echo [ERROR] Failed to build extension bundle
        cd ..
        pause
        exit /b 1
    )
) else (
    echo VS Code extension is already running in Docker container...
)

echo.
echo ========================================
echo   Installation Complete!
echo ========================================
echo.
echo Services running at:
echo   - API Gateway:     http://localhost:80
echo   - Auth Service:    http://localhost:8086
echo   - OWL Editor:      http://localhost:8083
echo   - SWRL Service:    http://localhost:8084
echo   - Plugin Service:  http://localhost:8087
echo   - MongoDB:         mongodb://localhost:27017
echo   - GraphDB:         http://localhost:7200
echo.

if %NODE_AVAILABLE%==1 (
    echo Starting VS Code Web Editor locally...
    echo The editor will open in your default browser.
    echo.
    echo ========================================

    REM Launch VS Code web extension
    start cmd /k "npm run test-web"
    cd ..
) else (
    echo.
    echo VS Code Web Editor (Docker): http://localhost:3000
    echo.
    echo ========================================
    echo Opening VS Code Web Editor in your browser...
    timeout /t 3 /nobreak >nul
    start http://localhost:3000
)

echo.
echo To stop all services, run: docker compose down
echo.
pause
