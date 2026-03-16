@echo off
REM Build multi-platform Docker images and push to registry
REM Supports both Intel (amd64) and Apple Silicon (arm64) Macs
REM Usage: build-and-push.bat [registry] [version]
REM
REM NOTE: vscode-web build is DISABLED - webapp build is enabled

set REGISTRY=%1
set VERSION=%2

if "%REGISTRY%"=="" set REGISTRY=sindhujacoretopia
if "%VERSION%"=="" set VERSION=latest

echo ============================================
echo    Building Multi-Platform OntoCode Images
echo    Registry: %REGISTRY%
echo    Version: %VERSION%
echo    Platforms: linux/amd64, linux/arm64
echo    Note: vscode-web build DISABLED
echo ============================================
echo.

REM Pre-flight checks disabled - vscode-web build is disabled
REM if not exist "ontology-vscode-extension\docker-entrypoint.sh" (
REM     echo ERROR: ontology-vscode-extension\docker-entrypoint.sh not found!
REM     echo This file is required to patch the Extension Host CSP at runtime.
REM     echo Please restore it before building.
REM     pause
REM     exit /b 1
REM )

echo Setting up buildx for multi-platform builds...
docker buildx create --name ontocode-builder --use --driver docker-container 2>nul
docker buildx inspect --bootstrap
echo.

echo Building and pushing multi-platform images...
echo This may take a while as images are built for both Intel and ARM architectures...
echo.

echo [1/8] Building ontocode-graphdb...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-graphdb:%VERSION% -f Dockerfile.graphdb --push .
if errorlevel 1 goto :error

echo [2/8] Building ontocode-auth...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-auth:%VERSION% -f Dockerfile.auth --push .
if errorlevel 1 goto :error

echo [3/8] Building ontocode-gateway...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-gateway:%VERSION% -f Dockerfile.gateway --push .
if errorlevel 1 goto :error

echo [4/8] Building ontocode-editor...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-editor:%VERSION% -f Dockerfile.editor --push .
if errorlevel 1 goto :error

echo [5/8] Building ontocode-swrl...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-swrl:%VERSION% -f Dockerfile.swrl --push .
if errorlevel 1 goto :error

echo [6/8] Building ontocode-plugin...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-plugin:%VERSION% -f Dockerfile.plugin --push .
if errorlevel 1 goto :error

echo [7/8] Building ontocode-plugin-init...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-plugin-init:%VERSION% -f Dockerfile.plugin-init --push .
if errorlevel 1 goto :error

echo [8/8] Building ontocode-web (webapp with HTTPS config)...
docker buildx build --no-cache --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-web:%VERSION% -f Dockerfile.webapp --push .
if errorlevel 1 goto :error

REM DISABLED: ontocode-vscode-web build
REM echo [8/8] Building ontocode-vscode-web (with CSP entrypoint)...
REM docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-vscode-web:%VERSION% -f Dockerfile.vscode-extension --push .
REM if errorlevel 1 goto :error

echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.

echo ============================================
echo    SUCCESS! All 8 images built and pushed!
echo ============================================
echo.
echo Multi-platform support:
echo   * Intel/AMD (linux/amd64)
echo   * Apple Silicon M1/M2/M3 (linux/arm64)
echo.
echo Images built:
echo   1. ontocode-graphdb - GraphDB triple store
echo   2. ontocode-auth - Authentication service
echo   3. ontocode-gateway - API gateway
echo   4. ontocode-editor - Ontology editor service
echo   5. ontocode-swrl - SWRL reasoner service
echo   6. ontocode-plugin - Plugin runtime service
echo   7. ontocode-plugin-init - Plugin initializer
echo   8. ontocode-web - React webapp (with HTTPS backend)
echo.
echo NOTE: ontocode-vscode-web build is DISABLED
echo.
echo To deploy:
echo   DOCKER_REGISTRY=%REGISTRY% docker compose up -d
echo.
echo Or create .env file with:
echo   DOCKER_REGISTRY=%REGISTRY%
echo   VERSION=%VERSION%
echo.
echo Then run:
echo   docker compose up -d
echo.
echo WEBAPP CONFIGURATION:
echo   Backend URL: https://ontocodeapi.selfresearch.org
echo   Frontend: https://ontocode.selfresearch.org
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
