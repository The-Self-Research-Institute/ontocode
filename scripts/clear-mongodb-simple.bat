@echo off
echo ╔════════════════════════════════════════╗
echo ║   MongoDB Cleanup Script               ║
echo ╚════════════════════════════════════════╝
echo.

set MONGODB_URI=mongodb://localhost:27017/ontocode

echo Checking for MongoDB CLI tools...
echo.

REM Try mongosh first
where mongosh >nul 2>&1
if not errorlevel 1 (
    echo Found mongosh! Cleaning MongoDB...
    echo.
    mongosh %MONGODB_URI% --quiet --eval "print('Collections before cleanup:'); db.getCollectionNames().forEach(function(c) { print('  - ' + c + ': ' + db[c].countDocuments() + ' documents'); }); print(''); print('Dropping collections...'); db.getCollectionNames().forEach(function(c) { print('  Dropping: ' + c); db[c].drop(); }); print(''); print('✓ MongoDB cleaned successfully'); print(''); print('Remaining collections:'); var remaining = db.getCollectionNames(); if (remaining.length === 0) { print('  (none)'); } else { remaining.forEach(function(c) { print('  - ' + c); }); }"
    echo.
    echo ╔════════════════════════════════════════╗
    echo ║   ✓ MongoDB Cleaned Successfully       ║
    echo ╚════════════════════════════════════════╝
    goto :end
)

REM Try mongo if mongosh not found
where mongo >nul 2>&1
if not errorlevel 1 (
    echo Found mongo! Cleaning MongoDB...
    echo.
    mongo %MONGODB_URI% --quiet --eval "print('Collections before cleanup:'); db.getCollectionNames().forEach(function(c) { print('  - ' + c + ': ' + db[c].count() + ' documents'); }); print(''); print('Dropping collections...'); db.getCollectionNames().forEach(function(c) { print('  Dropping: ' + c); db[c].drop(); }); print(''); print('✓ MongoDB cleaned successfully');"
    echo.
    echo ╔════════════════════════════════════════╗
    echo ║   ✓ MongoDB Cleaned Successfully       ║
    echo ╚════════════════════════════════════════╝
    goto :end
)

REM Neither tool found
echo ✗ MongoDB CLI not found!
echo.
echo Please install one of:
echo   - mongosh (recommended): https://www.mongodb.com/try/download/shell
echo   - mongo (legacy): Included with MongoDB
echo.
echo Or clean manually using MongoDB Compass:
echo   1. Open MongoDB Compass
echo   2. Connect to: mongodb://localhost:27017
echo   3. Delete the 'ontocode' database
echo.
echo ╔════════════════════════════════════════╗
echo ║   ✗ MongoDB CLI Not Found              ║
echo ╚════════════════════════════════════════╝

:end
echo.
pause
