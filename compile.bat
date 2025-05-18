@echo off
REM Compile script for Stonebreak

REM Set Java paths
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_441
set PATH=%JAVA_HOME%\bin;%PATH%

REM Set directories
set SRC_DIR=src\main\java
set TARGET_DIR=target\classes
set LIBS=

REM Create target directory if it doesn't exist
if not exist %TARGET_DIR% mkdir %TARGET_DIR%

REM Compile the files
echo Compiling Stonebreak files...
javac -d %TARGET_DIR% %SRC_DIR%\com\stonebreak\*.java

REM Check if compilation was successful
if %ERRORLEVEL% EQU 0 (
    echo Compilation successful!
) else (
    echo Compilation failed!
)

REM Pause to see the output
pause
