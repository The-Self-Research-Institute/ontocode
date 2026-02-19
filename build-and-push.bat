@echo off
REM Build multi-platform Docker images and push to registry
REM Supports both Intel (amd64) and Apple Silicon (arm64) Macs
REM Usage: build-and-push.bat [registry] [version]
REM
REM The vscode-web image includes a docker-entrypoint.sh that patches
REM the VS Code Extension Host CSP at container startup, allowing
REM HTTP connections to external API servers (e.g. cloud gateway).

set REGISTRY=%1
set VERSION=%2

if "%REGISTRY%"=="" set REGISTRY=sindhujacoretopia
if "%VERSION%"=="" set VERSION=latest

echo ============================================
echo    Building Multi-Platform OntoCode Images
echo    Registry: %REGISTRY%
echo    Version: %VERSION%
echo    Platforms: linux/amd64, linux/arm64
echo ============================================
echo.

REM --- Pre-flight: ensure docker-entrypoint.sh exists for vscode-web ---
if not exist "ontology-vscode-extension\docker-entrypoint.sh" (
    echo ERROR: ontology-vscode-extension\docker-entrypoint.sh not found!
    echo This file is required to patch the Extension Host CSP at runtime.
    echo Please restore it before building.
    pause
    exit /b 1
)

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

echo [8/8] Building ontocode-vscode-web (with CSP entrypoint)...
docker buildx build --platform linux/amd64,linux/arm64 -t %REGISTRY%/ontocode-vscode-web:%VERSION% -f Dockerfile.vscode-extension --push .
if errorlevel 1 goto :error

echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.

echo.
echo Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul
echo.

REM --- Optional: Patch CSP in local .vscode-test-web for dev testing ---
echo Patching local Extension Host CSP for development...
for /r "ontology-vscode-extension\.vscode-test-web" %%f in (webWorkerExtensionHostIframe.html) do (
    powershell -Command "(Get-Content '%%f') -replace \"connect-src 'self' https: wss: http://localhost:\* http://127.0.0.1:\* ws://localhost:\* ws://127.0.0.1:\*\", \"connect-src 'self' http: https: wss: ws: http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:*\" | Set-Content '%%f'" 2>nul
    echo   Patched: %%f
)
echo.

echo ============================================
echo    SUCCESS! All images built and pushed!
echo ============================================
echo.
echo Multi-platform support:
echo   * Intel/AMD (linux/amd64)
echo   * Apple Silicon M1/M2/M3 (linux/arm64)
echo.
echo Mac users can now run:
echo   DOCKER_REGISTRY=%REGISTRY% docker compose up -d
echo.
echo Or create .env file with:
echo   DOCKER_REGISTRY=%REGISTRY%
echo   VERSION=%VERSION%
echo.
echo Then run:
echo   docker compose up -d
echo.
echo NOTE: The vscode-web container automatically patches
echo the Extension Host CSP at startup via docker-entrypoint.sh
echo to allow HTTP connections to external API servers.
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
