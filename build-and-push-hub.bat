@echo off
set /p DOCKER_USER="Enter your Docker Hub username: "

echo.
echo ==============================================
echo Building and Pushing OntoCode Images for Production
echo Docker User: %DOCKER_USER%
echo ==============================================
echo.

echo 1. Login to Docker Hub...
docker login
if %errorlevel% neq 0 exit /b %errorlevel%

echo.
echo 2. Building Images...

echo Building vscode-web...
docker build -f Dockerfile.vscode-extension -t %DOCKER_USER%/ontocode-vscode-web:latest .
echo Building graphdb...
docker build -f Dockerfile.graphdb -t %DOCKER_USER%/ontocode-graphdb:latest .
echo Building gateway...
docker build -f Dockerfile.gateway -t %DOCKER_USER%/ontocode-gateway:latest ontology-gateway
echo Building editor...
docker build -f Dockerfile.editor -t %DOCKER_USER%/ontocode-editor:latest ontology-editor
echo Building auth...
docker build -f Dockerfile.auth -t %DOCKER_USER%/ontocode-auth:latest ontology-auth
echo Building plugin-service...
docker build -f Dockerfile.plugin -t %DOCKER_USER%/ontocode-plugin-service:latest ontology-plugin-service
echo Building plugin-init...
docker build -f Dockerfile.plugin-init -t %DOCKER_USER%/ontocode-plugin-init:latest .
echo Building swrl...
docker build -f Dockerfile.swrl -t %DOCKER_USER%/ontocode-swrl:latest ontology-swrl

echo.
echo 3. Pushing Images to Docker Hub...

docker push %DOCKER_USER%/ontocode-vscode-web:latest
docker push %DOCKER_USER%/ontocode-graphdb:latest
docker push %DOCKER_USER%/ontocode-gateway:latest
docker push %DOCKER_USER%/ontocode-editor:latest
docker push %DOCKER_USER%/ontocode-auth:latest
docker push %DOCKER_USER%/ontocode-plugin-service:latest
docker push %DOCKER_USER%/ontocode-plugin-init:latest
docker push %DOCKER_USER%/ontocode-swrl:latest

echo.
echo ==============================================
echo DONE! 
echo.
echo To deploy on your server, create a docker-compose.yml that uses these images:
echo   image: %DOCKER_USER%/ontocode-vscode-web:latest
echo   ...
echo ==============================================
pause
