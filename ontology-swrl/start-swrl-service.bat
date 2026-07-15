@echo off
REM SWRL Service Startup Script
REM 
REM IMPORTANT: This service requires Java 17 due to SWRLAPI/Drools/MVEL compatibility issues.
REM The Drools 7.x rule engine bundled with SWRLAPI 2.1.2 is incompatible with Java 21+
REM because MVEL attempts to generate classes in the java.lang package, which is prohibited.
REM
REM To install Java 17:
REM   - Download from: https://adoptium.net/temurin/releases/?version=17
REM   - Or use: winget install EclipseAdoptium.Temurin.17.JDK
REM   - Or use Chocolatey: choco install temurin17

REM Auto-detect Java 17 installation
SET JAVA_17_HOME=
FOR /D %%G IN ("C:\Program Files\Eclipse Adoptium\jdk-17*") DO SET JAVA_17_HOME=%%G

IF NOT DEFINED JAVA_17_HOME (
    FOR /D %%G IN ("C:\Program Files\Java\jdk-17*") DO SET JAVA_17_HOME=%%G
)

IF NOT DEFINED JAVA_17_HOME (
    echo ERROR: Java 17 not found!
    echo.
    echo Please install Java 17:
    echo   winget install EclipseAdoptium.Temurin.17.JDK
    echo.
    pause
    exit /b 1
)

IF NOT EXIST "%JAVA_17_HOME%\bin\java.exe" (
    echo ERROR: Java 17 not found at %JAVA_17_HOME%
    pause
    exit /b 1
)

echo Starting SWRL Service with Java 17...
echo JAVA_17_HOME: %JAVA_17_HOME%

cd /d "%~dp0"

"%JAVA_17_HOME%\bin\java.exe" -jar target\ontology-swrl-1.0.0.jar

pause
