@echo off
setlocal

set "ROOT_DIR=%~dp0.."
set "COMPOSE_FILE=%ROOT_DIR%\\docker-compose.yml"
set "SERVICES=mongo graphdb graphdb-init"

echo.
echo ========================================
echo   Starting Infrastructure (Docker)
echo ========================================
echo.

pushd "%ROOT_DIR%"
docker-compose -f "%COMPOSE_FILE%" up -d %SERVICES%
if errorlevel 1 (
    echo [ERROR] Failed to start infrastructure services.
    popd
    exit /b 1
)
popd

echo.
echo Infrastructure services are starting.
echo Logs: docker-compose -f "%COMPOSE_FILE%" logs -f %SERVICES%
