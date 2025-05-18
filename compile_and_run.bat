@echo off
REM Compile and run script for Stonebreak

REM Set Java paths
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_441
set PATH=%JAVA_HOME%\bin;%PATH%

REM Set directories
set SRC_DIR=src\main\java
set TARGET_DIR=target\classes
set LIB_DIR=lib

REM Create class path with all JAR files in lib directory
setlocal EnableDelayedExpansion
set CLASSPATH=.
for %%f in (%LIB_DIR%\*.jar) do (
    set CLASSPATH=!CLASSPATH!;%%f
)

REM Create target directory if it doesn't exist
if not exist %TARGET_DIR% mkdir %TARGET_DIR%

REM Clean previous class files to ensure a fresh compile
echo Cleaning previous build...
if exist "%TARGET_DIR%\com\stonebreak\*.class" (
    del /Q "%TARGET_DIR%\com\stonebreak\*.class"
)

REM Compile the files
echo Compiling Stonebreak files...
javac -cp %CLASSPATH% -d %TARGET_DIR% %SRC_DIR%\com\stonebreak\*.java

REM Check if compilation was successful
if %ERRORLEVEL% EQU 0 (
    echo Compilation successful!
    echo Starting Stonebreak...
    java -cp %TARGET_DIR%;%CLASSPATH% com.stonebreak.Main
) else (
    echo Compilation failed!
)

REM Pause to see the output
pause
