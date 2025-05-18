@echo off
REM Compile and run Stonebreak using Java 8

REM Set paths
set SRC_DIR=src\main\java
set OUT_DIR=target\classes
set LIB_DIR=lib
set CLASSPATH=%LIB_DIR%\*

REM Create output directory if it doesn't exist
mkdir %OUT_DIR% 2>nul

echo Compiling project...
javac -d %OUT_DIR% -cp "%CLASSPATH%" %SRC_DIR%\com\stonebreak\*.java

if %ERRORLEVEL% == 0 (
    echo Compilation successful!
    echo Starting Stonebreak...
    java -cp "%OUT_DIR%;%CLASSPATH%" com.stonebreak.Main
) else (
    echo Compilation failed!
)
