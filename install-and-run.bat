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

REM [3/5] Check/Pull images
echo.
echo [3/5] Checking images...
REM Check if main image exists
docker images %REGISTRY%/ontocode-gateway:%VERSION% --format "{{.Repository}}" 2>nul | findstr "ontocode-gateway" >nul 2>&1
if !errorlevel! equ 0 (
    echo [INFO] Images already available
    echo [OK] Images ready
) else (
    echo [INFO] Pulling pre-built images from %REGISTRY%...
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
)

REM [4/5] Start services
echo.
echo [4/5] Checking and starting services...
REM Check if services are already running
docker compose ps --services --filter "status=running" 2>nul | findstr "ontology-gateway" >nul 2>&1
if !errorlevel! equ 0 (
    echo [INFO] Services are already running
    echo [OK] All services active
) else (
    echo [INFO] Starting services...
    docker compose down >nul 2>&1
    set DOCKER_REGISTRY=%REGISTRY%
    docker compose up -d
    if errorlevel 1 (
        echo [ERROR] Failed to start services. Check errors above.
        pause
        exit /b 1
    )
    echo [OK] All services started
)

REM [5/6] Create desktop shortcut
echo.
echo [5/6] Creating desktop shortcut...
set SCRIPT_DIR=%~dp0
set DESKTOP=%USERPROFILE%\Desktop
powershell -Command "$WshShell = New-Object -comObject WScript.Shell; $Shortcut = $WshShell.CreateShortcut('%DESKTOP%\OntoCode.lnk'); $Shortcut.TargetPath = '%SCRIPT_DIR%install-and-run.bat'; $Shortcut.WorkingDirectory = '%SCRIPT_DIR%'; $Shortcut.Description = 'One-click launcher for OntoCode'; $Shortcut.Save()" >nul 2>&1
if exist "%DESKTOP%\OntoCode.lnk" (
    echo [OK] Desktop shortcut created
) else (
    echo [WARN] Could not create desktop shortcut
)

REM [6/6] Wait and open
echo.
echo [6/6] Waiting for services to be ready...
REM Check if this is first start or restart
docker compose ps --services --filter "status=running" 2>nul | findstr "ontology-gateway" >nul 2>&1
if !errorlevel! neq 0 (
    timeout /t 40 /nobreak >nul
) else (
    timeout /t 5 /nobreak >nul
)
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
