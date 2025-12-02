@echo off
echo ============================================
echo   Fixing MongoDB Authentication Issue
echo ============================================
echo.
echo This will:
echo   1. Stop all Docker containers
echo   2. Remove MongoDB data volume (reset database)
echo   3. Restart with correct credentials
echo.
echo WARNING: This will DELETE all data in MongoDB!
echo.
set /p confirm="Type 'yes' to continue: "
if not "%confirm%"=="yes" (
    echo Cancelled.
    pause
    exit /b 0
)

echo.
echo [1/4] Stopping all containers...
docker-compose down

echo.
echo [2/4] Removing MongoDB volume...
docker volume rm ontocode_mongo-data 2>nul
if errorlevel 1 (
    echo MongoDB volume may not exist or already removed.
)

echo.
echo [3/4] Verifying .env file has correct password...
findstr /C:"MONGO_ROOT_PASSWORD=changeme123" .env >nul
if errorlevel 1 (
    echo ERROR: .env file doesn't have MONGO_ROOT_PASSWORD=changeme123
    echo Please ensure .env file has the correct password.
    pause
    exit /b 1
)
echo Credentials verified!

echo.
echo [4/4] Starting services with clean MongoDB...
docker-compose up -d

echo.
echo ============================================
echo   MongoDB Reset Complete!
echo ============================================
echo.
echo Services are starting with credentials:
echo   Username: admin
echo   Password: changeme123
echo   Database: ontocode
echo.
echo Wait 30-60 seconds for services to initialize...
echo.
echo To check status: docker-compose ps
echo To view logs: docker-compose logs -f
echo.
pause
