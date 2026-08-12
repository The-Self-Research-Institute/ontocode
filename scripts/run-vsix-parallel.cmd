@echo off
REM Helper: build local VSIX via WSL and write exit code file.
REM Args: <repo-dir> <distro> <mode> <deploy> <build-platforms> <log-file> <exit-file>
setlocal
set "REPO=%~1"
set "DISTRO=%~2"
set "MODE=%~3"
set "DEPLOY=%~4"
set "BPLAT=%~5"
set "LOG=%~6"
set "EXITF=%~7"
wsl -d %DISTRO% --cd "%REPO%" -e bash -lc "export BUILD_PLATFORMS='%BPLAT%'; chmod +x ./scripts/deploy-coretopia-release.sh 2>/dev/null; ./scripts/deploy-coretopia-release.sh --mode %MODE% --platform vscode --changes '%DEPLOY%'" > "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
> "%EXITF%" echo %RC%
exit /b %RC%
