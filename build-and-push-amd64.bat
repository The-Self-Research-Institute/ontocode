@echo off
REM Build AMD64-only Docker images (faster, no ARM64)
REM Use this if you're only deploying to Intel/AMD servers
REM Usage: build-and-push-amd64.bat [registry] [version]

set REGISTRY=%1
set VERSION=%2

if "%REGISTRY%"=="" set REGISTRY=sindhujacoretopia
if "%VERSION%"=="" set VERSION=latest

echo ============================================
echo    Building AMD64-Only OntoCode Images
echo    Registry: %REGISTRY%
echo    Version: %VERSION%
echo    Platform: linux/amd64 ONLY
echo ============================================
echo.
echo NOTE: This build is faster but will NOT work on
echo Apple Silicon Macs (M1/M2/M3). Use build-and-push.bat
echo for multi-platform builds.
echo.

if not exist "ontology-vscode-extension\docker-entrypoint.sh" (
    echo ERROR: ontology-vscode-extension\docker-entrypoint.sh not found!
    pause
    exit /b 1
)

echo Setting up buildx...
docker buildx create --name ontocode-builder --use --driver docker-container 2>nul
docker buildx inspect --bootstrap
echo.

echo Building and pushing AMD64 images...
echo.

echo [1/8] Building ontocode-graphdb...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-graphdb:%VERSION% -f Dockerfile.graphdb --push .
if errorlevel 1 goto :error

echo [2/8] Building ontocode-auth...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-auth:%VERSION% -f Dockerfile.auth --push .
if errorlevel 1 goto :error

echo [3/8] Building ontocode-gateway...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-gateway:%VERSION% -f Dockerfile.gateway --push .
if errorlevel 1 goto :error

echo [4/8] Building ontocode-editor...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-editor:%VERSION% -f Dockerfile.editor --push .
if errorlevel 1 goto :error

echo [5/8] Building ontocode-swrl...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-swrl:%VERSION% -f Dockerfile.swrl --push .
if errorlevel 1 goto :error

echo [6/8] Building ontocode-plugin...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-plugin:%VERSION% -f Dockerfile.plugin --push .
if errorlevel 1 goto :error

echo [7/8] Building ontocode-plugin-init...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-plugin-init:%VERSION% -f Dockerfile.plugin-init --push .
if errorlevel 1 goto :error

echo [8/8] Building ontocode-vscode-web...
docker buildx build --platform linux/amd64 -t sindhujacoretopia/ontocode-vscode-web:latest -f Dockerfile.vscode-extension --push .
if errorlevel 1 goto :error

echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.

echo ============================================
echo    SUCCESS! AMD64 images built and pushed!
echo ============================================
echo.
echo To deploy:
echo   DOCKER_REGISTRY=%REGISTRY% docker compose up -d
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
pause
exit /b 1
