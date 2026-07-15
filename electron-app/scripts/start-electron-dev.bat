@echo off
REM OntoCode Electron — Dev Hot-Reload Script
REM
REM What this does:
REM   1. Starts Docker services (MongoDB, Fuseki, Spring Boot) via docker-compose.dev.yml
REM   2. Starts the Vite dev server for the React UI (port 5173)
REM   3. Starts Electron pointing at Docker + Vite (no bundled JARs needed)
REM
REM Prerequisites:
REM   - Docker Desktop running
REM   - Node.js installed
REM   - Run from the repo root OR from electron-app/

setlocal EnableDelayedExpansion

REM Resolve repo root regardless of where this script is called from
set "SCRIPT_DIR=%~dp0"
set "ELECTRON_DIR=%SCRIPT_DIR%.."
set "REPO_ROOT=%ELECTRON_DIR%\.."
set "WEBVIEW_DIR=%REPO_ROOT%\ontology-vscode-extension\webview-src"

echo ================================================
echo  OntoCode Electron — Dev Mode
echo ================================================
echo.

REM ── Step 1: Start Docker services ───────────────────────────────────────────
echo [1/3] Starting Docker backend services...
cd /d "%REPO_ROOT%"
docker compose -f docker-compose.dev.yml up -d
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to start Docker services. Is Docker Desktop running?
    pause
    exit /b 1
)
echo       Docker services started.
echo.

REM ── Step 2: Start Vite dev server in a new window ───────────────────────────
echo [2/3] Starting Vite dev server (port 5173)...
cd /d "%WEBVIEW_DIR%"

REM Install deps if node_modules missing
if not exist "node_modules" (
    echo       Installing frontend dependencies...
    call npm install
)

start "OntoCode Vite Dev Server" cmd /k "npm run dev"
echo       Vite started in a new window.
echo       Waiting 5 seconds for Vite to be ready...
timeout /t 5 /nobreak >nul
echo.

REM ── Step 3: Start Electron ───────────────────────────────────────────────────
echo [3/3] Starting Electron (dev mode, connects to Docker + Vite)...
cd /d "%ELECTRON_DIR%"

if not exist "node_modules" (
    echo       Installing electron dependencies...
    call npm install
)

set ELECTRON_IS_DEV=1
set ELECTRON_DEV_API_URL=http://localhost:8083
set ELECTRON_VITE_URL=http://localhost:5173

call npx electron .

echo.
echo Electron closed. Docker services are still running.
echo To stop Docker: docker compose -f docker-compose.dev.yml down
