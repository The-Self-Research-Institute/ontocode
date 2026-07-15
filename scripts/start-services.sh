@echo off
echo.
echo Stopping Ontology Platform Services...
echo.

:: Stop services by finding and killing processes on specific ports
echo Stopping Auth Service (port 8086)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8086" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Stopping Gateway (port 8082)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8082" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Stopping OWL Editor (port 8083)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8083" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Stopping SWRL Service (port 8084)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8084" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo.
echo [92mAll services stopped.[0m
echo.
echo Infrastructure (MongoDB, GraphDB) is still running.
echo To stop them manually:
echo   MongoDB:  net stop MongoDB
echo   GraphDB:  Close GraphDB window or process
echo.
pause