@echo off
REM Build OntoCode Desktop
REM Usage: build-desktop.bat [platform] [steps...]
REM Platforms: win | mac | linux | all  (default: win)
REM Steps: desktop  swrl  jars  ui  pack

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "JARS_DIR=%SCRIPT_DIR%\electron-app\resources\backend\jars"
set "DESKTOP_VERSION=1.0.0"
set "SWRL_VERSION=1.0.0"

REM --- Auto-detect Maven if not in PATH ---
where mvn >nul 2>&1
if errorlevel 1 (
    for /d %%D in ("C:\ProgramData\chocolatey\lib\maven\apache-maven-*") do (
        if exist "%%D\bin\mvn.cmd" set "PATH=%%D\bin;%PATH%"
    )
)
where mvn >nul 2>&1
if errorlevel 1 (
    echo ERROR: mvn not found. Run: choco install maven
    exit /b 1
)

REM --- Parse platform ---
set "PLATFORM=win"
set "ARG1=%~1"
if /i "%ARG1%"=="win"   ( set "PLATFORM=win"   & shift )
if /i "%ARG1%"=="mac"   ( set "PLATFORM=mac"   & shift )
if /i "%ARG1%"=="linux" ( set "PLATFORM=linux" & shift )
if /i "%ARG1%"=="all"   ( set "PLATFORM=all"   & shift )

REM --- Parse steps ---
set "RUN_DESKTOP=0"
set "RUN_SWRL=0"
set "RUN_UI=0"
set "RUN_PACK=0"
set "HAS_FILTER=0"

:parse_args
if "%~1"=="" goto done_parse
set "HAS_FILTER=1"
if /i "%~1"=="desktop" set "RUN_DESKTOP=1"
if /i "%~1"=="swrl"    set "RUN_SWRL=1"
if /i "%~1"=="jars"    ( set "RUN_DESKTOP=1" & set "RUN_SWRL=1" )
if /i "%~1"=="ui"      set "RUN_UI=1"
if /i "%~1"=="pack"    set "RUN_PACK=1"
shift
goto parse_args
:done_parse

if "%HAS_FILTER%"=="0" (
    set "RUN_DESKTOP=1"
    set "RUN_SWRL=1"
    set "RUN_UI=1"
    set "RUN_PACK=1"
)

echo.
echo ============================================
echo    Building OntoCode Desktop
echo    Platform : %PLATFORM%
echo    Root     : %SCRIPT_DIR%
echo ============================================
echo.

if not exist "%JARS_DIR%" mkdir "%JARS_DIR%"

if "%RUN_DESKTOP%"=="1" call :step_desktop
if errorlevel 1 goto fail

if "%RUN_SWRL%"=="1" call :step_swrl
if errorlevel 1 goto fail

if "%RUN_UI%"=="1" call :step_ui
if errorlevel 1 goto fail

if "%RUN_PACK%"=="1" call :step_pack
if errorlevel 1 goto fail

echo.
echo ============================================
echo    SUCCESS
echo ============================================
echo.
exit /b 0

:fail
echo.
echo ============================================
echo    BUILD FAILED
echo ============================================
echo.
exit /b 1

REM ============================================================
REM  Subroutines
REM ============================================================

:step_desktop
echo [1/4] Installing root parent POM...
cd /d "%SCRIPT_DIR%"
call mvn install -N -q
if errorlevel 1 ( echo FAILED & exit /b 1 )
echo      OK

echo [2/4] Installing shared modules...
call mvn install -pl "shared/common-models,shared/common-utils" -DskipTests -q
if errorlevel 1 ( echo FAILED & exit /b 1 )
echo      OK

echo [3/4] Installing auth + editor + plugin...
call mvn install -pl "ontology-auth,ontology-editor" -DskipTests -q
if errorlevel 1 ( echo FAILED & exit /b 1 )
cd /d "%SCRIPT_DIR%\ontology-plugin-service"
call mvn install -DskipTests -q
if errorlevel 1 ( echo FAILED & exit /b 1 )
cd /d "%SCRIPT_DIR%"
echo      OK

echo [4/4] Building ontology-desktop (combined JAR)...
if exist "%SCRIPT_DIR%\ontology-desktop\target" rmdir /s /q "%SCRIPT_DIR%\ontology-desktop\target"
cd /d "%SCRIPT_DIR%\ontology-desktop"
call mvn package -DskipTests -q
if errorlevel 1 ( echo FAILED & exit /b 1 )
cd /d "%SCRIPT_DIR%"

set "SRC=%SCRIPT_DIR%\ontology-desktop\target\ontology-desktop-%DESKTOP_VERSION%.jar"
if not exist "!SRC!" ( echo ERROR: JAR not found: !SRC! & exit /b 1 )
copy /y "!SRC!" "%JARS_DIR%\desktop.jar" >nul
echo      OK - desktop.jar copied
exit /b 0

:step_swrl
echo [swrl] Building swrl.jar...
cd /d "%SCRIPT_DIR%"
call mvn package -pl ontology-swrl -DskipTests -q
if errorlevel 1 ( echo FAILED & exit /b 1 )
set "SRC=%SCRIPT_DIR%\ontology-swrl\target\ontology-swrl-%SWRL_VERSION%.jar"
if not exist "!SRC!" ( echo ERROR: JAR not found & exit /b 1 )
copy /y "!SRC!" "%JARS_DIR%\swrl.jar" >nul
echo      OK - swrl.jar copied
exit /b 0

:step_ui
echo [ui] Building React UI...
cd /d "%SCRIPT_DIR%\ontology-vscode-extension\webview-src"
call npm run build:electron
if errorlevel 1 ( echo FAILED & exit /b 1 )
cd /d "%SCRIPT_DIR%"
echo      OK - React UI built
exit /b 0

:step_pack
echo [pack] Packaging Electron app for %PLATFORM%...
cd /d "%SCRIPT_DIR%\electron-app"
if /i "%PLATFORM%"=="win"   call npx electron-builder --win
if /i "%PLATFORM%"=="mac"   call npx electron-builder --mac
if /i "%PLATFORM%"=="linux" call npx electron-builder --linux
if /i "%PLATFORM%"=="all"   call npx electron-builder --win --mac --linux
if errorlevel 1 ( echo FAILED & exit /b 1 )
cd /d "%SCRIPT_DIR%"
echo      OK - packaged to electron-app\dist\
exit /b 0
