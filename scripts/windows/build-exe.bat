@echo off
REM ========================================
REM Build OntoCode Launcher Executable
REM ========================================

echo ========================================
echo   Building OntoCode Launcher
echo ========================================
echo.

REM Try to find C# compiler
set CSC_PATH=

REM Check PATH first
where csc >nul 2>&1
if !errorlevel! equ 0 (
    set CSC_PATH=csc
    goto :compile
)

REM Search for .NET Framework compiler
echo Searching for C# compiler...
for %%v in (v4.8.9209 v4.0.30319) do (
    if exist "C:\Windows\Microsoft.NET\Framework64\%%v\csc.exe" (
        set CSC_PATH=C:\Windows\Microsoft.NET\Framework64\%%v\csc.exe
        goto :compile
    )
)

if "%CSC_PATH%"=="" (
    echo [ERROR] C# compiler not found.
    echo.
    echo Please install one of the following:
    echo   1. Visual Studio Build Tools: https://visualstudio.microsoft.com/downloads/
    echo   2. .NET SDK: https://dotnet.microsoft.com/download
    echo.
    echo Or run this from Visual Studio Developer Command Prompt
    pause
    exit /b 1
)

:compile
echo Using compiler: %CSC_PATH%
echo Compiling OntoCodeLauncher.cs...
echo.

"%CSC_PATH%" /out:OntoCodeLauncher.exe /target:exe /win32icon:"%SystemRoot%\System32\imageres.dll,11" OntoCodeLauncher.cs 2>nul

if not exist OntoCodeLauncher.exe (
    REM Try without icon if first attempt fails
    "%CSC_PATH%" /out:OntoCodeLauncher.exe /target:exe OntoCodeLauncher.cs
)

if exist OntoCodeLauncher.exe (
    echo.
    echo ========================================
    echo   Success!
    echo ========================================
    echo.
    echo OntoCodeLauncher.exe has been created!
    echo File size: 
    dir OntoCodeLauncher.exe | findstr "OntoCodeLauncher.exe"
    echo.
    echo You can now:
    echo   1. Double-click OntoCodeLauncher.exe to start OntoCode
    echo   2. Share this .exe file with others
    echo   3. Run it to automatically create desktop shortcut
    echo.
    echo The .exe will automatically:
    echo   - Check Docker status
    echo   - Pull/start services
    echo   - Create desktop shortcut
    echo   - Open http://localhost:3000
    echo.
) else (
    echo.
    echo [ERROR] Failed to build executable
    echo Check if OntoCodeLauncher.cs exists in current directory
    echo.
)

pause
