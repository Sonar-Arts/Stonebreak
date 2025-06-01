@echo off
:: This script sets up Java 17 and runs Maven with it
:: To use: mvn17 clean package

:: Set Java 17 environment
SET "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
SET "PATH=%JAVA_HOME%\bin;%PATH%"

:: Display Java version being used
echo Using Java:
java -version
echo.

:: Run Maven with the provided arguments
echo Running: mvn %*
call mvn %*

:: Check exit code
if %ERRORLEVEL% EQU 0 (
    echo.
    echo Maven build successful!
) else (
    echo.
    echo Maven build failed with exit code: %ERRORLEVEL%
)
