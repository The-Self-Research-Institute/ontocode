@echo off
setlocal enabledelayedexpansion

:: Colors for output (Windows 10+)
set "GREEN=[92m"
set "YELLOW=[93m"
set "RED=[91m"
set "BLUE=[94m"
set "NC=[0m"

echo.
echo ========================================
echo   Starting Ontology Platform
echo ========================================
echo.

:: Check if Maven daemon is available
where mvn >nul 2>&1
if %errorlevel% equ 0 (
    set MVN_CMD=mvnd
    echo Using Maven Daemon for faster builds...
) else (
    set MVN_CMD=mvn
    echo Using standard Maven...
)

:: ========================================
:: Check Prerequisites
:: ========================================
echo.
echo %BLUE%[1/7] Checking Prerequisites...%NC%
echo.

:: Check MongoDB
echo Checking MongoDB...
curl -s http://localhost:27017 >nul 2>&1
if %errorlevel% neq 0 (
    echo %YELLOW%Warning: MongoDB is not reachable on port 27017%NC%
    echo   MongoDB is required for ontology metadata storage.
) else (
    echo %GREEN%MongoDB is running!%NC%
)

:: Check GraphDB
echo Checking GraphDB...
curl -s http://localhost:7200/rest/repositories/ontocode >nul 2>&1
if %errorlevel% neq 0 (
    echo %YELLOW%Warning: GraphDB is not reachable on port 7200%NC%
    echo   GraphDB is required for SPARQL triple store.
    echo   Start GraphDB and create 'ontocode' repository before proceeding.
) else (
    echo %GREEN%GraphDB is running with 'ontocode' repository!%NC%
)

:: ========================================
:: Build All Modules
:: ========================================
echo.
echo %BLUE%[2/7] Building all modules...%NC%
echo.
cd /d %~dp0..
%MVN_CMD% clean install -DskipTests -T 1C
if %errorlevel% neq 0 (
    echo %RED%Build failed! Check logs above.%NC%
    pause
    exit /b 1
)
echo %GREEN%Build successful!%NC%

:: ========================================
:: Start Auth Service
:: ========================================
echo.
echo %BLUE%[3/7] Starting Auth Service...%NC%
start "Ontology Auth Service" cmd /k "cd /d %~dp0..\ontology-auth && %MVN_CMD% spring-boot:run -Dspring-boot.run.profiles=dev"
timeout /t 10 /nobreak >nul

:: Health check for Auth
echo Checking Auth Service health...
set AUTH_READY=0
for /l %%i in (1,1,30) do (
    curl -s http://localhost:8086/actuator/health >nul 2>&1
    if !errorlevel! equ 0 (
        set AUTH_READY=1
        goto auth_ready
    )
    timeout /t 2 /nobreak >nul
)
:auth_ready
if %AUTH_READY% equ 1 (
    echo %GREEN%Auth Service is running on port 8086%NC%
) else (
    echo %YELLOW%Warning: Auth Service may not be ready yet%NC%
)

:: ========================================
:: Start Gateway
:: ========================================
echo.
echo %BLUE%[4/7] Starting Gateway...%NC%
start "Ontology Gateway" cmd /k "cd /d %~dp0..\ontology-gateway && %MVN_CMD% spring-boot:run -Dspring-boot.run.profiles=dev"
timeout /t 10 /nobreak >nul

:: Health check for Gateway
echo Checking Gateway health...
set GATEWAY_READY=0
for /l %%i in (1,1,30) do (
    curl -s http://localhost:8082/actuator/health >nul 2>&1
    if !errorlevel! equ 0 (
        set GATEWAY_READY=1
        goto gateway_ready
    )
    timeout /t 2 /nobreak >nul
)
:gateway_ready
if %GATEWAY_READY% equ 1 (
    echo %GREEN%Gateway is running on port 8082%NC%
) else (
    echo %YELLOW%Warning: Gateway may not be ready yet%NC%
)

:: ========================================
:: Start OWL Editor
:: ========================================
echo.
echo %BLUE%[5/7] Starting OWL Editor...%NC%
start "OWL Editor Service" cmd /k "cd /d %~dp0..\ontology-editor && %MVN_CMD% spring-boot:run -Dspring-boot.run.profiles=dev"
timeout /t 15 /nobreak >nul

:: Health check for OWL Editor
echo Checking OWL Editor health...
set OWL_READY=0
for /l %%i in (1,1,30) do (
    curl -s http://localhost:8083/actuator/health >nul 2>&1
    if !errorlevel! equ 0 (
        set OWL_READY=1
        goto owl_ready
    )
    timeout /t 2 /nobreak >nul
)
:owl_ready
if %OWL_READY% equ 1 (
    echo %GREEN%OWL Editor is running on port 8083%NC%
) else (
    echo %YELLOW%Warning: OWL Editor may not be ready yet%NC%
)

:: ========================================
:: Start SWRL Service
:: ========================================
echo.
echo %BLUE%[6/7] Starting SWRL Service...%NC%
start "SWRL Service" cmd /k "cd /d %~dp0..\ontology-swrl && %MVN_CMD% spring-boot:run -Dspring-boot.run.profiles=dev"
timeout /t 10 /nobreak >nul

:: Health check for SWRL
echo Checking SWRL Service health...
set SWRL_READY=0
for /l %%i in (1,1,30) do (
    curl -s http://localhost:8084/actuator/health >nul 2>&1
    if !errorlevel! equ 0 (
        set SWRL_READY=1
        goto swrl_ready
    )
    timeout /t 2 /nobreak >nul
)
:swrl_ready
if %SWRL_READY% equ 1 (
    echo %GREEN%SWRL Service is running on port 8084%NC%
) else (
    echo %YELLOW%Warning: SWRL Service may not be ready yet%NC%
)

:: ========================================
:: Summary
:: ========================================
echo.
echo ========================================
echo   %GREEN%All Services Started!%NC%
echo ========================================
echo.
echo %YELLOW%Service URLs:%NC%
echo    Auth Service:    http://localhost:8086
echo    Gateway:         http://localhost:8082
echo    OWL Editor:      http://localhost:8083
echo    SWRL Service:    http://localhost:8084
echo.
echo %YELLOW%Infrastructure:%NC%
echo    MongoDB:         http://localhost:27017
echo    GraphDB:         http://localhost:7200
echo    GraphDB Repo:    http://localhost:7200/repositories/ontocode
echo.
echo %YELLOW%API Documentation:%NC%
echo    Gateway Swagger: http://localhost:8082/swagger-ui.html
echo    OWL API Docs:    http://localhost:8083/swagger-ui.html
echo.
echo %YELLOW%Management:%NC%
echo    Press Ctrl+C in any window to stop that service
echo    Run %~dp0stop-all.bat to stop all services
echo    Run %~dp0health-check.bat to check service status
echo.
pause