@echo off
REM Run script for Stonebreak

REM Set Java paths
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_441
set PATH=%JAVA_HOME%\bin;%PATH%

REM Set directories
set TARGET_DIR=target\classes
set LIBS=

REM Check if the class files exist
if not exist %TARGET_DIR%\com\stonebreak\Main.class (
    echo Class files not found! Please compile the project first.
    goto end
)

REM Run the game
echo Starting Stonebreak...
java -Xmx2g -cp %TARGET_DIR% com.stonebreak.Main

:end
pause
