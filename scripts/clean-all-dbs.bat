@echo off
echo.
echo ╔════════════════════════════════════════════════════╗
echo ║   Complete Database Cleanup                        ║
echo ║   This will delete ALL data from:                  ║
echo ║   - GraphDB (ontocode repository)                  ║
echo ║   - MongoDB (ontocode database)                    ║
echo ╚════════════════════════════════════════════════════╝
echo.
echo Press Ctrl+C to cancel, or
pause

echo.
echo ════════════════════════════════════════════════════
echo   STEP 1: Cleaning GraphDB
echo ════════════════════════════════════════════════════
echo.
call clear-graphdb-simple.bat

echo.
echo.
echo ════════════════════════════════════════════════════
echo   STEP 2: Cleaning MongoDB
echo ════════════════════════════════════════════════════
echo.
call clear-mongodb-simple.bat

echo.
echo.
echo ╔════════════════════════════════════════════════════╗
echo ║   ✓ Complete Cleanup Finished                      ║
echo ╚════════════════════════════════════════════════════╝
echo.
echo Next Steps:
echo   1. Restart your backend services if running
echo   2. Upload a new ontology file
echo   3. System will recreate collections automatically
echo.
pause
