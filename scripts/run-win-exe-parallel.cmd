@echo off
REM Helper: build Windows .exe and write exit code file.
REM Args: <repo-root> <log-file> <exit-file>
setlocal
set "ROOT=%~1"
set "LOG=%~2"
set "EXITF=%~3"

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
> "%EXITF%" echo %RC%
exit /b %RC%
