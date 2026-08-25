@echo off
REM ========================================
REM Create Desktop Shortcut for OntoCode
REM ========================================

echo Creating desktop shortcut for OntoCode...

set SCRIPT_DIR=%~dp0
set DESKTOP=%USERPROFILE%\Desktop
set SHORTCUT_NAME=Start OntoCode.lnk

REM Use PowerShell to create the shortcut
powershell -Command "$WshShell = New-Object -comObject WScript.Shell; $Shortcut = $WshShell.CreateShortcut('%DESKTOP%\%SHORTCUT_NAME%'); $Shortcut.TargetPath = '%SCRIPT_DIR%install-and-run.bat'; $Shortcut.WorkingDirectory = '%SCRIPT_DIR%'; $Shortcut.Description = 'One-click installation and launch for OntoCode'; $Shortcut.Save()"

if exist "%DESKTOP%\%SHORTCUT_NAME%" (
    echo.
    echo ========================================
    echo   Success!
    echo ========================================
    echo.
    echo Desktop shortcut created at:
    echo %DESKTOP%\%SHORTCUT_NAME%
    echo.
    echo You can now double-click the shortcut to start OntoCode!
    echo.
) else (
    echo.
    echo [ERROR] Failed to create shortcut
    echo Please run this script as administrator
    echo.
)

pause
