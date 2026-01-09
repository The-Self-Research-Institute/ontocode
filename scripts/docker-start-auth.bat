@echo off
setlocal

set "ROOT_DIR=%~dp0.."
set "COMPOSE_FILE=%ROOT_DIR%\\docker-compose.yml"
set "SERVICE=auth"

echo.
echo ========================================
echo   Starting %SERVICE% (Docker)
echo ========================================
echo.

pushd "%ROOT_DIR%"
docker-compose -f "%COMPOSE_FILE%" up -d --build %SERVICE%
if errorlevel 1 (
    echo [ERROR] Failed to start %SERVICE%.
    popd
    exit /b 1
)
popd

echo.
echo %SERVICE% is starting.
echo Logs: docker-compose -f "%COMPOSE_FILE%" logs -f %SERVICE%
