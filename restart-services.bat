@echo off
echo ================================================
echo   OntoCode Services Restart
echo ================================================
echo.
echo This will restart the gateway and ontology-editor services
echo to apply the graph view fix.
echo.
pause

echo.
echo [1/2] Starting Gateway Service...
echo ================================================
cd ontology-gateway
start "OntoCode Gateway (8082)" cmd /k "mvn spring-boot:run"
timeout /t 5

echo.
echo [2/2] Starting Ontology Editor Service...
echo ================================================
cd ..\ontology-editor
start "OntoCode Editor (8083)" cmd /k "mvn spring-boot:run"

echo.
echo ================================================
echo   Services Starting...
echo ================================================
echo.
echo Gateway:          http://localhost:8082
echo Ontology Editor:  http://localhost:8083
echo.
echo Wait 30-60 seconds for services to fully start
echo Then open: http://localhost:3000
echo.
echo Press any key to close this window...
pause > nul
