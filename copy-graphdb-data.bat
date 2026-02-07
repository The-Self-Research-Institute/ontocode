@echo off
echo ===============================================
echo Copy GraphDB Data from C: to D: Drive
echo ===============================================
echo.

echo Copying repository data...
xcopy /E /I /Y "%APPDATA%\GraphDB\data" "D:\GraphDB-Data\data"

echo.
echo Done! GraphDB data copied to D:\GraphDB-Data
echo.
echo Next steps:
echo 1. Start GraphDB
echo 2. Check logs to verify it's using D: drive
echo 3. Test with ontology upload
echo.
pause
