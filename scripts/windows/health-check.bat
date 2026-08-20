@echo off
echo.
echo Checking service health...
echo.

:: Check MongoDB
curl -s http://localhost:27017 >nul 2>&1
if %errorlevel% equ 0 (
    echo [92m✓[0m MongoDB         - UP
) else (
    echo [91m✗[0m MongoDB         - DOWN
)

:: Check GraphDB
curl -s http://localhost:7200/rest/repositories >nul 2>&1
if %errorlevel% equ 0 (
    echo [92m✓[0m GraphDB         - UP
    
    :: Check if ontocode repository exists
    curl -s http://localhost:7200/rest/repositories | findstr "ontocode" >nul 2>&1
    if %errorlevel% equ 0 (
        echo   [92m✓[0m Repository 'ontocode' exists
    ) else (
        echo   [91m✗[0m Repository 'ontocode' NOT found
        echo      Create it at: http://localhost:7200/repository
        echo      See: GRAPHDB_SETUP.md for instructions
    )
) else (
    :: Try Fuseki
    curl -s http://localhost:3030 >nul 2>&1
    if %errorlevel% equ 0 (
        echo [92m✓[0m Fuseki          - UP
    ) else (
        echo [91m✗[0m SPARQL Endpoint - DOWN
        echo    GraphDB required: See GRAPHDB_SETUP.md
    )
)

:: Check Auth Service
curl -s http://localhost:8086/actuator/health >nul 2>&1
if %errorlevel% equ 0 (
    echo [92m✓[0m Auth Service    - UP
) else (
    echo [91m✗[0m Auth Service    - DOWN
)

:: Check Gateway
curl -s http://localhost:8082/actuator/health >nul 2>&1
if %errorlevel% equ 0 (
    echo [92m✓[0m Gateway         - UP
) else (
    echo [91m✗[0m Gateway         - DOWN
)

:: Check OWL Editor
curl -s http://localhost:8083/actuator/health >nul 2>&1
if %errorlevel% equ 0 (
    echo [92m✓[0m OWL Editor      - UP
) else (
    echo [91m✗[0m OWL Editor      - DOWN
)

:: Check SWRL Service
curl -s http://localhost:8084/actuator/health >nul 2>&1
if %errorlevel% equ 0 (
    echo [92m✓[0m SWRL Service    - UP
) else (
    echo [91m✗[0m SWRL Service    - DOWN
)

echo.
pause