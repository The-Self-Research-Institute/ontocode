@echo off
REM Start OntoCode Development Services

echo ========================================
echo Starting OntoCode Development Services
echo ========================================
echo.

REM Build local service images so newly added routes/controllers are present.
docker compose -f docker-compose.dev.yml up -d --build

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo Services started successfully!
    echo ========================================
    echo.
    echo Services running at:
    echo   Gateway:        http://localhost:80
    echo   Auth Service:   http://localhost:8086
    echo   OWL Editor:     http://localhost:8083
    echo   SWRL Service:   http://localhost:8084
    echo   Plugin Service: http://localhost:8087
    echo   GraphDB:        http://localhost:7200
    echo   MongoDB:        mongodb://localhost:27017
    echo   VS Code Web:    http://localhost:3000
    echo.
    echo Local source images were rebuilt before startup.
    echo To view logs:     docker compose -f docker-compose.dev.yml logs -f
    echo To stop services: docker compose -f docker-compose.dev.yml down
    echo.
) else (
    echo.
    echo ========================================
    echo Failed to start services!
    echo ========================================
    echo.
)
