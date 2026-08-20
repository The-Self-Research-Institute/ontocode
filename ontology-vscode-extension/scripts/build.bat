@echo off
REM Build script for ontology-vscode-extension
REM Runs webview build, bundle:web, and compile in sequence

echo ========================================
echo Building Ontology VSCode Extension
echo ========================================
echo.

REM Step 1: Build webview
echo [1/3] Building webview...
cd webview-src
call npm run build
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Webview build failed!
    cd ..
    exit /b %ERRORLEVEL%
)
cd ..
echo [1/3] Webview build completed successfully
echo.

REM Step 2: Bundle web assets
echo [2/3] Bundling web assets...
call npm run bundle:web
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Web bundling failed!
    exit /b %ERRORLEVEL%
)
echo [2/3] Web bundling completed successfully
echo.

REM Step 3: Compile TypeScript
echo [3/3] Compiling TypeScript...
call npm run compile
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: TypeScript compilation failed!
    exit /b %ERRORLEVEL%
)
echo [3/3] TypeScript compilation completed successfully
echo.

echo ========================================
echo Build completed successfully!
echo ========================================
