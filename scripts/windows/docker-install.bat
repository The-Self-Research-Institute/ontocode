@echo off
REM ========================================
REM OntoCode - Docker-Only Installation
REM No Node.js Required - Everything in Docker!
REM ========================================

setlocal enabledelayedexpansion

echo.
echo ========================================
echo   OntoCode Docker-Only Installation
echo   (No Node.js Required!)
echo ========================================
echo.

REM Check if Docker is running
echo [1/4] Checking Docker...
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
echo.
echo [2/5] Preparing workspace directory...
if not exist "data\projects" (
    echo Creating data\projects directory...
    mkdir data\projects
)
echo [OK] Workspace directory ready

REM Stop any existing containers
echo.
echo [3/5] Cleaning up existing containers...
docker compose down -v >nul 2>&1
echo [OK] Cleanup complete

REM Build and start all Docker services INCLUDING VS Code Web
echo.
echo [4/5] Building and starting all services...
echo This includes: MongoDB, GraphDB, Auth, Gateway, Editor, SWRL, Plugins, and VS Code Web
echo First run may take 5-10 minutes...
docker compose up -d --build
if errorlevel 1 (
    echo [ERROR] Failed to start Docker services
    echo Check the error messages above
    pause
    exit /b 1
)
echo [OK] All Docker services started successfully

REM Wait for services to be ready
echo.
echo [5/5] Waiting for all services to initialize...
echo This includes starting the VS Code web server...
timeout /t 45 /nobreak >nul
echo [OK] Services should be ready

echo.
echo ========================================
echo   Installation Complete!
echo ========================================
echo.
echo All services running in Docker:
echo.
echo   PRIMARY ACCESS:
echo   - VS Code Web Editor:  http://localhost:3000
echo      ^(Open this in your browser to start editing^)
echo.
echo   BACKEND SERVICES:
echo   - API Gateway:         http://localhost:80
echo   - Auth Service:        http://localhost:8086
echo   - OWL Editor:          http://localhost:8083
echo   - SWRL Service:        http://localhost:8084
echo   - Plugin Service:      http://localhost:8087
echo   - MongoDB:             mongodb://localhost:27017
echo   - GraphDB:             http://localhost:7200
echo.
echo ========================================
echo.
echo Opening VS Code Web Editor in your browser...
timeout /t 3 /nobreak >nul
start http://localhost:3000
echo.
echo To stop all services: docker compose down
echo To view logs: docker compose logs -f vscode-web
echo.
pause
