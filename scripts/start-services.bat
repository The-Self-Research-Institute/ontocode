@echo off
echo Starting Ontology Platform...

REM Check MongoDB
docker ps | findstr ontology-mongodb >nul 2>&1
if %errorlevel% neq 0 (
    echo Starting MongoDB...
    docker run -d -p 27017:27017 --name ontology-mongodb mongo:6.0
    timeout /t 5 /nobreak
)

echo.
echo Starting Auth Service...
start "Auth Service" cmd /k "cd /d %~dp0ontology-auth && mvn spring-boot:run"
timeout /t 10 /nobreak

echo Starting Gateway...
start "Gateway" cmd /k "cd /d %~dp0ontology-gateway && mvn spring-boot:run"
timeout /t 10 /nobreak

echo Starting OWL Editor...
start "OWL Editor" cmd /k "cd /d %~dp0ontology-editor && mvn spring-boot:run"

echo.
echo ========================================
echo All services starting!
echo ========================================
echo.
echo Service URLs:
echo    Auth:       http://localhost:8083
echo    Gateway:    http://localhost:8082
echo    OWL Editor: http://localhost:8086
echo.
pause