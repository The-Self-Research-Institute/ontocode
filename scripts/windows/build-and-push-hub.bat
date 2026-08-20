@echo off
set /p DOCKER_USER="Enter your Docker Hub username: "

echo.
echo ==============================================
echo Building and Pushing OntoCode Multi-Platform Images
echo Docker User: %DOCKER_USER%
echo Platforms: linux/amd64, linux/arm64 (Mac M1/M2/M3 compatible)
echo Note: vscode-web build DISABLED
echo ==============================================
echo.

echo 1. Login to Docker Hub...
docker login
if %errorlevel% neq 0 exit /b %errorlevel%

echo.
echo 2. Setting up buildx for multi-platform builds...
docker buildx create --name ontocode-builder --use --driver docker-container 2>nul
docker buildx inspect --bootstrap

echo.
echo 3. Building and Pushing Multi-Platform Images...
echo    This may take a while as images are built for both Intel and ARM architectures...
echo.

REM DISABLED: vscode-web build
REM echo Building vscode-web...
REM docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.vscode-extension -t %DOCKER_USER%/ontocode-vscode-web:latest --push .

echo Building webapp...
docker buildx build --no-cache --platform linux/amd64,linux/arm64 -f Dockerfile.webapp -t %DOCKER_USER%/ontocode-web:latest --push .
echo Building graphdb...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.graphdb -t %DOCKER_USER%/ontocode-graphdb:latest --push .
echo Building gateway...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.gateway -t %DOCKER_USER%/ontocode-gateway:latest --push .
echo Building editor...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.editor -t %DOCKER_USER%/ontocode-editor:latest --push .
echo Building auth...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.auth -t %DOCKER_USER%/ontocode-auth:latest --push .
echo Building plugin-service...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.plugin -t %DOCKER_USER%/ontocode-plugin-service:latest --push .
echo Building plugin-init...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.plugin-init -t %DOCKER_USER%/ontocode-plugin-init:latest --push .
echo Building swrl...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.swrl -t %DOCKER_USER%/ontocode-swrl:latest --push .

echo Building swrl...
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile.swrl -t %DOCKER_USER%/ontocode-swrl:latest --push .

echo.
echo 4. Cleaning up buildx builder...
docker buildx rm ontocode-builder 2>nul

echo.
echo ==============================================
echo SUCCESS! All images built and pushed!
echo.
echo Multi-platform support:
echo   ✓ Intel/AMD (linux/amd64)
echo   ✓ Apple Silicon M1/M2/M3 (linux/arm64)
echo.
echo Users can now run on any platform:
echo   docker pull %DOCKER_USER%/ontocode-web:latest
echo   docker compose up -d
echo.
echo Images available:
echo   - %DOCKER_USER%/ontocode-web:latest (webapp with HTTPS backend)
echo   - %DOCKER_USER%/ontocode-graphdb:latest
echo   - %DOCKER_USER%/ontocode-gateway:latest
echo   - %DOCKER_USER%/ontocode-editor:latest
echo   - %DOCKER_USER%/ontocode-auth:latest
echo   - %DOCKER_USER%/ontocode-plugin-service:latest
echo   - %DOCKER_USER%/ontocode-plugin-init:latest
echo   - %DOCKER_USER%/ontocode-swrl:latest
echo.
echo NOTE: ontocode-vscode-web build is DISABLED
echo.
echo WEBAPP CONFIGURATION:
echo   Backend URL: https://ontocodeapi.selfresearch.org
echo ==============================================
pause
