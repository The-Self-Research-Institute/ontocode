@echo off
echo ╔════════════════════════════════════════╗
echo ║   Database Cleanup Script              ║
echo ╚════════════════════════════════════════╝
echo.

set GRAPHDB_URL=http://localhost:7200
set GRAPHDB_REPO=ontocode

echo === Step 1: Clearing GraphDB ===
echo Repository: %GRAPHDB_URL%/repositories/%GRAPHDB_REPO%
echo.

REM Check if GraphDB is running
echo Checking GraphDB connectivity...
curl -s -f "%GRAPHDB_URL%/rest/repositories" >nul 2>&1
if errorlevel 1 (
    echo [WARNING] Cannot connect to GraphDB at %GRAPHDB_URL%
    echo GraphDB may not be running or accessible.
    echo.
) else (
    echo [OK] GraphDB is accessible
    echo.

    echo Clearing all triples...
    curl -X POST "%GRAPHDB_URL%/repositories/%GRAPHDB_REPO%/statements" ^
        -H "Content-Type: application/x-www-form-urlencoded" ^
        --data-urlencode "update=DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }" ^
        -s -o nul -w "HTTP Status: %%{http_code}\n"

    if errorlevel 1 (
        echo [WARNING] GraphDB cleanup may have failed
    ) else (
        echo [OK] GraphDB cleared successfully
    )
    echo.
)

echo.
echo === Step 2: MongoDB Cleanup Instructions ===
echo.
echo MongoDB cannot be automatically cleared from this script.
echo Please clear MongoDB manually using ONE of these methods:
echo.
echo Method 1 - MongoDB Shell:
echo   mongosh mongodb://localhost:27017/ontocode
echo   Then run: db.getCollectionNames().forEach(c =^> db[c].drop())
echo.
echo Method 2 - MongoDB Compass:
echo   1. Open MongoDB Compass
echo   2. Connect to mongodb://localhost:27017
echo   3. Delete the 'ontocode' database
echo.
echo Method 3 - Delete data folder (if safe):
echo   1. Stop MongoDB service
echo   2. Delete: C:\data\db or your MongoDB data folder
echo   3. Restart MongoDB service
echo.

echo.
echo ════════════════════════════════════════
echo.

REM Check for MongoDB CLI
where mongosh >nul 2>&1
if not errorlevel 1 (
    echo Found mongosh! Attempting MongoDB cleanup...
    echo.
    mongosh mongodb://localhost:27017/ontocode --quiet --eval "db.getCollectionNames().forEach(function(c) { print('Dropping: ' + c); db[c].drop(); }); print('\n[OK] MongoDB cleared');"
    if not errorlevel 1 (
        echo.
        echo ╔════════════════════════════════════════╗
        echo ║   ✓ All databases cleared successfully ║
        echo ╚════════════════════════════════════════╝
    )
) else (
    where mongo >nul 2>&1
    if not errorlevel 1 (
        echo Found mongo! Attempting MongoDB cleanup...
        echo.
        mongo mongodb://localhost:27017/ontocode --quiet --eval "db.getCollectionNames().forEach(function(c) { print('Dropping: ' + c); db[c].drop(); }); print('\n[OK] MongoDB cleared');"
        if not errorlevel 1 (
            echo.
            echo ╔════════════════════════════════════════╗
            echo ║   ✓ All databases cleared successfully ║
            echo ╚════════════════════════════════════════╝
        )
    ) else (
        echo.
        echo [INFO] MongoDB CLI not found in PATH
        echo Please use one of the manual methods above
        echo.
        echo ╔════════════════════════════════════════╗
        echo ║   GraphDB cleared, MongoDB - manual    ║
        echo ╚════════════════════════════════════════╝
    )
)

echo.
pause
