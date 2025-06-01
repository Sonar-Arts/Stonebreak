@echo off
:: This script helps you compile and run your Stonebreak project with Java 17

:: Set Java 17 environment
SET "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
SET "PATH=%JAVA_HOME%\bin;%PATH%"

echo Current Java version:
java -version
echo.

echo Building with Maven using Java 17...
call mvn clean package -Dmaven.test.skip=true

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Build successful!
    echo Running Stonebreak...
    echo.
    java -jar target\stonebreak-1.0-SNAPSHOT.jar
) else (
    echo.
    echo Build failed with error code: %ERRORLEVEL%
)

pause
