@echo off
REM Build all Docker images and push to registry
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
echo    Building and Pushing OntoCode Images
echo    Registry: %REGISTRY%
echo    Version: %VERSION%
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

echo [1/8] Building ontocode-graphdb...
docker build -t %REGISTRY%/ontocode-graphdb:%VERSION% -f Dockerfile.graphdb .
if errorlevel 1 goto :error

echo [2/8] Building ontocode-auth...
docker build -t %REGISTRY%/ontocode-auth:%VERSION% -f Dockerfile.auth .
if errorlevel 1 goto :error

echo [3/8] Building ontocode-gateway...
docker build -t %REGISTRY%/ontocode-gateway:%VERSION% -f Dockerfile.gateway .
if errorlevel 1 goto :error

echo [4/8] Building ontocode-editor...
docker build -t %REGISTRY%/ontocode-editor:%VERSION% -f Dockerfile.editor .
if errorlevel 1 goto :error

echo [5/8] Building ontocode-swrl...
docker build -t %REGISTRY%/ontocode-swrl:%VERSION% -f Dockerfile.swrl .
if errorlevel 1 goto :error

echo [6/8] Building ontocode-plugin...
docker build -t %REGISTRY%/ontocode-plugin:%VERSION% -f Dockerfile.plugin .
if errorlevel 1 goto :error

echo [7/8] Building ontocode-plugin-init...
docker build -t %REGISTRY%/ontocode-plugin-init:%VERSION% -f Dockerfile.plugin-init .
if errorlevel 1 goto :error

echo [8/8] Building ontocode-vscode-web (with CSP entrypoint)...
docker build -t %REGISTRY%/ontocode-vscode-web:%VERSION% -f Dockerfile.vscode-extension .
if errorlevel 1 goto :error

echo.
echo ============================================
echo    All images built successfully!
echo ============================================
echo.

REM --- Optional: Patch CSP in local .vscode-test-web for dev testing ---
echo Patching local Extension Host CSP for development...
for /r "ontology-vscode-extension\.vscode-test-web" %%f in (webWorkerExtensionHostIframe.html) do (
    powershell -Command "(Get-Content '%%f') -replace \"connect-src 'self' https: wss: http://localhost:\* http://127.0.0.1:\* ws://localhost:\* ws://127.0.0.1:\*\", \"connect-src 'self' http: https: wss: ws: http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:*\" | Set-Content '%%f'" 2>nul
    echo   Patched: %%f
)
echo.

echo ============================================
echo    Pushing images to registry...
echo ============================================
echo.

docker push %REGISTRY%/ontocode-graphdb:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-graphdb

docker push %REGISTRY%/ontocode-auth:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-auth

docker push %REGISTRY%/ontocode-gateway:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-gateway

docker push %REGISTRY%/ontocode-editor:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-editor

docker push %REGISTRY%/ontocode-swrl:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-swrl

docker push %REGISTRY%/ontocode-plugin:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-plugin

docker push %REGISTRY%/ontocode-plugin-init:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-plugin-init

docker push %REGISTRY%/ontocode-vscode-web:%VERSION%
if errorlevel 1 echo WARNING: Failed to push ontocode-vscode-web

echo.
echo ============================================
echo    All images built and pushed successfully!
echo ============================================
echo.
echo Users can now run: docker compose up -d
echo Make sure to set DOCKER_REGISTRY in .env file
echo.
echo NOTE: The vscode-web container automatically patches
echo the Extension Host CSP at startup via docker-entrypoint.sh
echo to allow HTTP connections to external API servers.
echo.
pause
exit /b 0

:error
echo.
echo ============================================
echo    BUILD FAILED! See error above.
echo ============================================
pause
exit /b 1
