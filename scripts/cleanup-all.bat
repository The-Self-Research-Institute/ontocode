@echo off
echo.
echo ╔════════════════════════════════════════════════════╗
echo ║   Complete Cleanup (DBs + Long Queries)           ║
echo ╚════════════════════════════════════════════════════╝
echo.

REM Step 1: Kill long-running queries
echo [1/2] Cleaning up long-running queries...
node cleanup-long-queries.js
echo.

REM Step 2: Clear databases
echo [2/2] Clearing databases...
node clear-databases.js
echo.

echo ╔════════════════════════════════════════════════════╗
echo ║   ✓ Complete Cleanup Finished                     ║
echo ╚════════════════════════════════════════════════════╝
echo.
pause
