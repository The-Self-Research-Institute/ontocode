@echo off
REM OntoCode Docker Quick Start Script
REM This script helps you quickly start the OntoCode platform using Docker

echo.
echo ====================================
echo   OntoCode Docker Deployment
echo ====================================
echo.

REM Check if .env file exists
if not exist .env (
    echo [WARNING] No .env file found!
    echo.
    echo Creating .env from .env.production template...
    copy .env.production .env >nul
    echo.
    echo [IMPORTANT] Please edit .env file and change:
    echo   1. JWT_SECRET - Set a secure random string
    echo   2. MONGO_ROOT_PASSWORD - Change from default
    echo   3. Email settings if you want notifications
    echo.
    echo Press any key to open .env in notepad...
    pause >nul
    notepad .env
    echo.
    echo After saving .env, press any key to continue...
    pause >nul
)

echo.
echo Checking Docker...
docker --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not installed or not running!
    echo Please install Docker Desktop from: https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)

docker-compose --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Compose is not installed!
    echo Please install Docker Compose
    pause
    exit /b 1
)

echo Docker is ready!
echo.

REM Ask user what to do
echo What would you like to do?
echo.
echo [1] Start all services (without admin UI)
echo [2] Start all services (with MongoDB Express admin UI)
echo [3] Stop all services
echo [4] View logs
echo [5] Rebuild and restart
echo [6] Clean everything (WARNING: deletes all data!)
echo [0] Exit
echo.

set /p choice="Enter your choice (0-6): "

if "%choice%"=="1" goto start_basic
if "%choice%"=="2" goto start_with_admin
if "%choice%"=="3" goto stop
if "%choice%"=="4" goto logs
if "%choice%"=="5" goto rebuild
if "%choice%"=="6" goto clean
if "%choice%"=="0" goto end

echo Invalid choice!
goto end

:start_basic
echo.
echo Starting OntoCode services...
echo This may take a few minutes on first run (building images)...
echo.
docker-compose -f docker-compose.production.yml up -d
goto show_status

:start_with_admin
echo.
echo Starting OntoCode services with MongoDB Express...
echo This may take a few minutes on first run (building images)...
echo.
docker-compose -f docker-compose.production.yml --profile admin up -d
goto show_status

:stop
echo.
echo Stopping all services...
docker-compose -f docker-compose.production.yml down
echo.
echo All services stopped.
goto end

:logs
echo.
echo Showing logs (press Ctrl+C to exit)...
echo.
docker-compose -f docker-compose.production.yml logs -f
goto end

:rebuild
echo.
echo Rebuilding and restarting services...
echo This will take several minutes...
echo.
docker-compose -f docker-compose.production.yml down
docker-compose -f docker-compose.production.yml build --no-cache
docker-compose -f docker-compose.production.yml up -d
goto show_status

:clean
echo.
echo [WARNING] This will delete ALL data including databases!
echo.
set /p confirm="Are you sure? Type 'yes' to confirm: "
if not "%confirm%"=="yes" (
    echo Cancelled.
    goto end
)
echo.
echo Cleaning everything...
docker-compose -f docker-compose.production.yml down -v
docker system prune -f
echo.
echo Everything cleaned!
goto end

:show_status
echo.
echo ====================================
echo Services are starting...
echo ====================================
echo.
echo Waiting for services to be healthy (this may take 1-2 minutes)...
timeout /t 10 /nobreak >nul

echo.
echo Service Status:
docker-compose -f docker-compose.production.yml ps
echo.

echo ====================================
echo Access Points:
echo ====================================
echo API Gateway:      http://localhost:8082
echo Auth Service:     http://localhost:8086
echo Editor Service:   http://localhost:8083
echo SWRL Service:     http://localhost:8084
echo Plugin Service:   http://localhost:8087
echo GraphDB:          http://localhost:7200
echo MongoDB Express:  http://localhost:8081 (if started with admin profile)
echo.

echo ====================================
echo Next Steps:
echo ====================================
echo 1. Setup GraphDB repository 'ontocode' at http://localhost:7200
echo 2. Configure VS Code extension to use http://localhost:8082
echo 3. Test the services at http://localhost:8082/actuator/health
echo.
echo To view logs: docker-compose -f docker-compose.production.yml logs -f
echo To stop: docker-compose -f docker-compose.production.yml down
echo.

:end
echo.
pause
