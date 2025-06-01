@echo off
:: This script runs Maven with Java 17 and custom settings

:: Set Java 17 environment
SET "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
SET "PATH=%JAVA_HOME%\bin;%PATH%"

:: Display Java version being used
echo Using Java:
java -version
echo.

:: Run Maven with custom settings and provided arguments
echo Running Maven with Java 17 and custom settings...
call mvn -s .mvn\settings.xml %*

:: Check exit code
if %ERRORLEVEL% EQU 0 (
    echo.
    echo Maven build successful!
) else (
    echo.
    echo Maven build failed with exit code: %ERRORLEVEL%
)

pause
