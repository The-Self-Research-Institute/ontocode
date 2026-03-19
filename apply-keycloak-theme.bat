@echo off
REM Apply OntoCode Theme to Keycloak
REM This script restarts Keycloak to load the custom theme

echo ================================================
echo   OntoCode Keycloak Theme Installer
echo ================================================
echo.

REM Check if Docker is running
echo Checking Docker status...
docker ps >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop.
    pause
    exit /b 1
)
echo [OK] Docker is running

REM Check if Keycloak container exists
echo Checking for Keycloak container...
docker ps -a --filter "name=ontocode-keycloak" --format "{{.Names}}" | findstr "ontocode-keycloak" >nul
if errorlevel 1 (
    echo [ERROR] Keycloak container not found. Please run docker-compose first.
    pause
    exit /b 1
)
echo [OK] Found Keycloak container

REM Restart Keycloak to load the theme
echo.
echo Restarting Keycloak to load OntoCode theme...
docker-compose -f docker-compose.keycloak.yml restart keycloak

REM Wait for Keycloak to be ready
echo Waiting for Keycloak to start...
timeout /t 10 /nobreak >nul

echo.
echo ================================================
echo   Theme Installation Complete!
echo ================================================
echo.
echo Next Steps:
echo 1. Access Keycloak Admin Console:
echo    http://localhost:9080/admin
echo.
echo 2. Login with credentials:
echo    Username: admin
echo    Password: admin
echo.
echo 3. Configure Realm Theme:
echo    - Select your realm (or create 'ontocode')
echo    - Go to: Realm Settings -^> Themes
echo    - Set Login Theme: ontocode
echo    - Set Account Theme: ontocode
echo    - Set Email Theme: ontocode
echo    - Click Save
echo.
echo 4. Or use CLI to apply theme automatically:
echo    .\apply-theme-cli.ps1
echo.
pause
