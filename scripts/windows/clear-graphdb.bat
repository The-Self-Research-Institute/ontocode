@echo off
REM GraphDB Cleanup Script for Windows
REM This script clears all data from the GraphDB repository

set GRAPHDB_URL=http://localhost:7200
set REPOSITORY=ontocode

echo === GraphDB Cleanup Script ===
echo GraphDB URL: %GRAPHDB_URL%
echo Repository: %REPOSITORY%
echo.

REM Check if GraphDB is running
echo Checking if GraphDB is accessible...
curl -s -f "%GRAPHDB_URL%/rest/repositories" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Cannot connect to GraphDB at %GRAPHDB_URL%
    echo Please ensure GraphDB is running.
    exit /b 1
)

echo [OK] GraphDB is accessible
echo.

REM Get current statement count
echo Checking current data...
set QUERY=SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }

REM Clear all statements
echo.
echo === Starting cleanup ===
echo Clearing all triples from repository: %REPOSITORY%
echo.

set UPDATE_QUERY=DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }

curl -X POST "%GRAPHDB_URL%/repositories/%REPOSITORY%/statements" ^
    -H "Content-Type: application/x-www-form-urlencoded" ^
    --data-urlencode "update=%UPDATE_QUERY%" ^
    -w "\nHTTP Status: %%{http_code}\n"

if errorlevel 1 (
    echo [ERROR] Failed to clear triples
    exit /b 1
) else (
    echo [OK] Successfully cleared all triples
)

echo.
echo === Cleanup complete ===
echo.
pause
