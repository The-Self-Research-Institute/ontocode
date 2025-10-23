    @echo off
    echo Starting Ontology Platform...

    @REM REM Check MongoDB
    @REM docker ps | findstr ontology-mongodb >nul 2>&1
    @REM if %errorlevel% neq 0 (
    @REM     echo Starting MongoDB...
    @REM     docker run -d -p 27017:27017 --name ontology-mongodb mongo:6.0
    @REM     timeout /t 5 /nobreak
    @REM )

    @REM echo.
    echo Starting Auth Service...
    start "Auth Service" cmd /k "cd /d %~dp0..\ontology-auth && mvnd spring-boot:run"
    timeout /t 10 /nobreak

    echo Starting Gateway...
    start "Gateway" cmd /k "cd /d %~dp0..\ontology-gateway && mvnd spring-boot:run"
    timeout /t 10 /nobreak

    echo Starting OWL Editor...
    start "OWL Editor" cmd /k "cd /d %~dp0..\ontology-editor && mvnd spring-boot:run"

    echo Starting Swrl Service...
    start "Swrl Service" cmd /k "cd /d %~dp0..\ontology-swrl && mvnd spring-boot:run"

    echo.
    echo ========================================
    echo All services starting!
    echo ========================================
    echo.
    echo Service URLs:
    echo    Auth:       http://localhost:8086
    echo    Gateway:    http://localhost:8082
    echo    OWL Editor: http://localhost:8083
    echo    SWRL Service: http://localhost:8084
    echo.
    pause