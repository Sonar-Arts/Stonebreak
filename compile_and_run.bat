@echo off
REM Compile and run script for Stonebreak using Maven

echo Building with Maven...

REM Try Maven Wrapper first, then fall back to global mvn
IF EXIST mvnw.cmd (
    echo Using Maven Wrapper (mvnw.cmd)...
    call mvnw.cmd clean package
) ELSE (
    echo Maven Wrapper (mvnw.cmd) not found. Trying global mvn...
    mvn clean package
)

REM Check if Maven build was successful
if %ERRORLEVEL% EQU 0 (
    echo Maven build successful!
    echo Starting Stonebreak from shaded JAR...
    java -jar target/stonebreak-1.0-SNAPSHOT.jar
) else (
    echo Maven build failed!
    echo Please ensure Maven is installed and in your PATH, or that mvnw.cmd is available.
)

REM Pause to see the output
pause
