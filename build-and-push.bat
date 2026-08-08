@echo off
REM OntoCode build-and-push (Windows)
REM Checks prerequisites, installs host npm deps, builds Docker images, pushes to registry.
REM
REM Usage:
REM   build-and-push.bat
REM   build-and-push.bat sindhujacoretopia latest
REM   build-and-push.bat sindhujacoretopia latest web editor
REM
REM Env:
REM   DOCKER_REGISTRY  default registry namespace (e.g. your Docker Hub user)
REM   VERSION          image tag (default: latest)
REM
REM Flags (PowerShell): pass after -- 
REM   build-and-push.bat -- -SkipHostDeps -Services web,swrl

setlocal EnableExtensions
cd /d "%~dp0"

set "REGISTRY=%~1"
set "VERSION=%~2"

if "%REGISTRY%"=="" if defined DOCKER_REGISTRY set "REGISTRY=%DOCKER_REGISTRY%"
if "%REGISTRY%"=="" set "REGISTRY=sindhujacoretopia"
if "%VERSION%"=="" if defined VERSION set "VERSION=%VERSION%"
if "%VERSION%"=="" set "VERSION=latest"

REM Collect optional service filters from args 3+
set "SERVICES="
shift
shift
:svc_loop
if "%~1"=="" goto svc_done
if "%SERVICES%"=="" (
  set "SERVICES=%~1"
) else (
  set "SERVICES=%SERVICES%,%~1"
)
shift
goto svc_loop
:svc_done

echo.
echo Launching build-and-push.ps1 ...
echo   Registry: %REGISTRY%
echo   Version : %VERSION%
if not "%SERVICES%"=="" echo   Services: %SERVICES%
echo.

where pwsh >nul 2>&1
if %ERRORLEVEL%==0 (
  if "%SERVICES%"=="" (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-and-push.ps1" -Registry "%REGISTRY%" -Version "%VERSION%"
  ) else (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-and-push.ps1" -Registry "%REGISTRY%" -Version "%VERSION%" -Services %SERVICES%
  )
) else (
  if "%SERVICES%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-and-push.ps1" -Registry "%REGISTRY%" -Version "%VERSION%"
  ) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-and-push.ps1" -Registry "%REGISTRY%" -Version "%VERSION%" -Services %SERVICES%
  )
)

set "EXITCODE=%ERRORLEVEL%"
if not "%EXITCODE%"=="0" (
  echo.
  echo BUILD/PUSH FAILED ^(exit %EXITCODE%^). See messages above.
  pause
  exit /b %EXITCODE%
)

echo.
pause
exit /b 0
