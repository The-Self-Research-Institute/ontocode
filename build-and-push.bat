@echo off
REM Build multi-platform Docker images and push to registry.
REM
REM Usage:
REM   build-and-push.bat [registry] [version] [service1] [service2] ...
REM
REM Examples:
REM   build-and-push.bat                                  -- build ALL services
REM   build-and-push.bat sindhujacoretopia latest editor  -- build only editor
REM   build-and-push.bat sindhujacoretopia latest auth editor gateway
REM
REM Available service names:
REM   graphdb  auth  gateway  editor  swrl  plugin  plugin-init  web

setlocal enabledelayedexpansion

set REGISTRY=%1
set VERSION=%2
if "%REGISTRY%"=="" set REGISTRY=sindhujacoretopia
if "%VERSION%"=="" set VERSION=latest

REM Collect service filter from args 3 onward
set FILTER=
set ARG_IDX=0
for %%A in (%*) do (
    set /a ARG_IDX+=1
    if !ARG_IDX! GTR 2 (
        set FILTER=!FILTER! %%A
    )
)
set FILTER=%FILTER:~1%

echo ============================================
echo    Building Multi-Platform OntoCode Images
echo    Registry : %REGISTRY%
echo    Version  : %VERSION%
echo    Platforms: linux/amd64, linux/arm64
if "%FILTER%"=="" (
    echo    Services : ALL
) else (
    echo    Services : %FILTER%
)
echo ============================================
echo.

echo Setting up buildx...
docker buildx create --name ontocode-builder --use --driver docker-container 2>nul
if errorlevel 1 docker buildx use ontocode-builder
docker buildx inspect --bootstrap
echo.

REM ── Build each service if it matches the filter (or no filter given) ────────

call :maybe_build graphdb     "[1/8] graphdb"      ontocode-graphdb      Dockerfile.graphdb      ""
call :maybe_build auth        "[2/8] auth"         ontocode-auth         Dockerfile.auth         ""
call :maybe_build gateway     "[3/8] gateway"      ontocode-gateway      Dockerfile.gateway      ""
call :maybe_build editor      "[4/8] editor"       ontocode-editor       Dockerfile.editor       ""
call :maybe_build swrl        "[5/8] swrl"         ontocode-swrl         Dockerfile.swrl         ""
call :maybe_build plugin      "[6/8] plugin"       ontocode-plugin       Dockerfile.plugin       ""
call :maybe_build plugin-init "[7/8] plugin-init"  ontocode-plugin-init  Dockerfile.plugin-init  ""
call :maybe_build web         "[8/8] web"          ontocode-web          Dockerfile.webapp       "--no-cache"

goto :success

REM ── Subroutine: build if service is in filter (or filter is empty) ───────────
:maybe_build
set SVC=%~1
set LABEL=%~2
set TAG=%~3
set FILE=%~4
set EXTRA=%~5

REM Skip if filter set and this service not in it
if not "%FILTER%"=="" (
    echo %FILTER% | findstr /i /w "%SVC%" >nul 2>&1
    if errorlevel 1 goto :eof
)

echo.
echo ── %LABEL% ──────────────────────────────────
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/%TAG%:%VERSION% -f %FILE% %EXTRA% --push .
if errorlevel 1 goto :error
echo [OK] %TAG% pushed
goto :eof

:success
echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.
echo ============================================
echo    SUCCESS!
echo ============================================
echo.
echo To deploy on EC2:
echo   docker compose pull ^&^& docker compose up -d
echo.
echo To deploy a single service on EC2:
echo   docker compose pull owl-editor ^&^& docker compose up -d owl-editor
echo.
pause
exit /b 0

:error
echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.
echo ============================================
echo    BUILD FAILED! See error above.
echo ============================================
echo.
echo If you see a buildx error, try:
echo   docker buildx rm ontocode-builder
echo   docker buildx prune -af
echo Then run this script again.
echo.
pause
exit /b 1
