@echo off
REM Helper: build Windows .exe and optionally upload to downloads API.
REM Args: <repo-root> <log-file> <exit-file> [mode]
REM   mode = dev|prod|all  — if set, after a successful dist:win upload via EC2
REM   (scripts/upload-win-exe-via-ec2.sh). Hybrid build-and-push.cmd should pass mode.
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~1"
set "LOG=%~2"
set "EXITF=%~3"
set "MODE=%~4"

REM Ensure webview-src has local node_modules (vite) before electron dist:win.
if exist "%ROOT%\ontology-vscode-extension\webview-src\package.json" (
  pushd "%ROOT%\ontology-vscode-extension\webview-src"
  if not exist node_modules\vite (
    echo [win-exe] installing webview-src deps...>> "%LOG%"
    call npm ci --no-fund --no-audit >> "%LOG%" 2>&1
    if errorlevel 1 call npm install --no-fund --no-audit >> "%LOG%" 2>&1
  )
  popd
)

cd /d "%ROOT%\electron-app"
call npm run dist:win >> "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  > "%EXITF%" echo %RC%
  exit /b %RC%
)

REM Optional upload (hybrid path). Prefer WSL Ubuntu-22.04; skip quietly if mode unset.
if not "%MODE%"=="" (
  echo [win-exe] uploading installer via EC2 ^(mode=%MODE%^)...>> "%LOG%"
  set "DISTRO=%WSL_DISTRO%"
  if "!DISTRO!"=="" set "DISTRO=Ubuntu-22.04"
  if /I "%MODE%"=="all" (
    wsl -d !DISTRO! --cd "%ROOT%" -e bash -lc "chmod +x ./scripts/upload-win-exe-via-ec2.sh 2>/dev/null; ./scripts/upload-win-exe-via-ec2.sh dev" >> "%LOG%" 2>&1
    if errorlevel 1 (
      echo [win-exe] DEV upload FAILED>> "%LOG%"
      set "RC=1"
    ) else (
      wsl -d !DISTRO! --cd "%ROOT%" -e bash -lc "chmod +x ./scripts/upload-win-exe-via-ec2.sh 2>/dev/null; ./scripts/upload-win-exe-via-ec2.sh prod" >> "%LOG%" 2>&1
      if errorlevel 1 (
        echo [win-exe] PROD upload FAILED>> "%LOG%"
        set "RC=1"
      )
    )
  ) else (
    wsl -d !DISTRO! --cd "%ROOT%" -e bash -lc "chmod +x ./scripts/upload-win-exe-via-ec2.sh 2>/dev/null; ./scripts/upload-win-exe-via-ec2.sh '%MODE%'" >> "%LOG%" 2>&1
    if errorlevel 1 (
      echo [win-exe] %MODE% upload FAILED>> "%LOG%"
      set "RC=1"
    ) else (
      echo [win-exe] %MODE% upload OK>> "%LOG%"
    )
  )
)

> "%EXITF%" echo !RC!
exit /b !RC!
