@echo off
REM Script to recreate GraphDB repository without inference for faster bulk loading

echo ========================================
echo Recreating GraphDB Repository (No Inference)
echo ========================================
echo.

REM Delete existing repository
echo [1/2] Deleting existing repository...
curl -X DELETE http://localhost:7200/repositories/ontocode
echo.
echo.

REM Wait a bit for deletion to complete
timeout /t 2 /nobreak > nul

REM Create new repository without inference
echo [2/2] Creating new repository without inference...
curl -X POST -F "config=@%~dp0repo-config-no-inference.ttl" http://localhost:7200/rest/repositories
echo.
echo.

echo ========================================
echo Repository recreated successfully!
echo Inference disabled for faster imports
echo ========================================
pause
