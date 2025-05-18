@echo off
REM Setup script for Stonebreak - Download required libraries

REM Create lib directory if it doesn't exist
if not exist lib mkdir lib

REM Download the required LWJGL libraries
echo Downloading LWJGL libraries...
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://build.lwjgl.org/release/3.3.2/lwjgl.zip' -OutFile 'lib\lwjgl.zip'}"

REM Extract the LWJGL libraries
echo Extracting LWJGL libraries...
powershell -Command "& {Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('lib\lwjgl.zip', 'lib')}"

echo Setup completed successfully!
echo Please run compile_and_run.bat to build and start the game.

pause
