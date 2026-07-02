@echo off
REM Build AMD64-only Docker images (faster, no ARM64)
REM Use this if you're only deploying to Intel/AMD servers
REM Usage: build-and-push-amd64.bat [registry] [version]
REM NOTE: vscode-web build is DISABLED - webapp build is enabled

set REGISTRY=%1
set VERSION=%2

if "%REGISTRY%"=="" set REGISTRY=sindhujacoretopia
if "%VERSION%"=="" set VERSION=latest

echo ============================================
echo    Building AMD64-Only OntoCode Images
echo    Registry: %REGISTRY%
echo    Version: %VERSION%
echo    Platform: linux/amd64 ONLY
echo    Note: vscode-web build DISABLED
echo ============================================
echo.
echo NOTE: This build is faster but will NOT work on
echo Apple Silicon Macs (M1/M2/M3). Use build-and-push.bat
echo for multi-platform builds.
echo.

REM Pre-flight checks disabled
REM if not exist "ontology-vscode-extension\docker-entrypoint.sh" (
REM     echo ERROR: ontology-vscode-extension\docker-entrypoint.sh not found!
REM     pause
REM     exit /b 1
REM )

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

echo [4/9] Building ontocode-editor...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-editor:%VERSION% -f Dockerfile.editor --push .
if errorlevel 1 goto :error

echo [5/9] Building ontocode-reasoner-worker...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-reasoner-worker:%VERSION% -f Dockerfile.reasoner-worker --push .
if errorlevel 1 goto :error

echo [6/9] Building ontocode-swrl...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-swrl:%VERSION% -f Dockerfile.swrl --push .
if errorlevel 1 goto :error

echo [7/9] Building ontocode-plugin...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-plugin:%VERSION% -f Dockerfile.plugin --push .
if errorlevel 1 goto :error

echo [8/9] Building ontocode-plugin-init...
docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-plugin-init:%VERSION% -f Dockerfile.plugin-init --push .
if errorlevel 1 goto :error

echo [9/9] Building ontocode-web (webapp with HTTPS config)...
docker buildx build --no-cache --platform linux/amd64 -t %REGISTRY%/ontocode-web:%VERSION% -f Dockerfile.webapp --push .
if errorlevel 1 goto :error

REM DISABLED: ontocode-vscode-web build
REM echo [8/8] Building ontocode-vscode-web...
REM docker buildx build --platform linux/amd64 -t %REGISTRY%/ontocode-vscode-web:%VERSION% -f Dockerfile.vscode-extension --push .
REM if errorlevel 1 goto :error

echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.

echo ============================================
echo    SUCCESS! AMD64 images built and pushed!
echo ============================================
echo.
echo Images built:
echo   1. ontocode-graphdb
echo   2. ontocode-auth
echo   3. ontocode-gateway
echo   4. ontocode-editor
echo   5. ontocode-swrl
echo   6. ontocode-plugin
echo   7. ontocode-plugin-init
echo   8. ontocode-web (webapp with HTTPS backend)
echo.
echo NOTE: ontocode-vscode-web build is DISABLED
echo.
echo To deploy:
echo   DOCKER_REGISTRY=%REGISTRY% docker compose up -d
echo.
echo WEBAPP CONFIGURATION:
echo   Backend URL: https://ontocodeapi.selfresearch.org
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
