@echo off
REM ========================================
REM OntoCode - System Status Check
REM ========================================

echo.
echo ========================================
echo   OntoCode System Status
echo ========================================
echo.

REM Check Docker
echo [1/3] Docker Status
echo -------------------
docker --version 2>nul
if errorlevel 1 (
    echo [X] Docker: NOT INSTALLED
) else (
    docker ps >nul 2>&1
    if errorlevel 1 (
        echo [X] Docker: INSTALLED but NOT RUNNING
    ) else (
        echo [OK] Docker: RUNNING
    )
)

echo.

REM Check Node.js
echo [2/3] Node.js Status
echo -------------------
node --version 2>nul
if errorlevel 1 (
    echo [X] Node.js: NOT INSTALLED
) else (
    echo [OK] Node.js: INSTALLED
    node --version
)

npm --version 2>nul
if errorlevel 1 (
    echo [X] NPM: NOT INSTALLED
) else (
    echo [OK] NPM: INSTALLED
    npm --version
)

echo.

REM Check Docker Containers
echo [3/3] Container Status
echo -------------------
docker compose ps 2>nul
if errorlevel 1 (
    echo [X] No containers running
    echo Run 'install-and-run.bat' to start all services
) else (
    echo.
    echo Container Details:
    docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"
)

echo.
echo ========================================
echo   Service URLs
echo ========================================
echo   API Gateway:     http://localhost:80
echo   Auth Service:    http://localhost:8086
echo   OWL Editor:      http://localhost:8083
echo   SWRL Service:    http://localhost:8084
echo   Plugin Service:  http://localhost:8087
echo   GraphDB:         http://localhost:7200
echo   MongoDB:         mongodb://localhost:27017
echo ========================================
echo.

pause
