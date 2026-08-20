@echo off
REM Stop OntoCode Development Services

echo ========================================
echo Stopping OntoCode Development Services
echo ========================================
echo.

docker compose -f docker-compose.dev.yml down

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Services stopped successfully!
    echo.
    echo To remove volumes (WARNING: deletes all data):
    echo   docker compose -f docker-compose.dev.yml down -v
    echo.
) else (
    echo.
    echo Failed to stop services!
    echo.
)
