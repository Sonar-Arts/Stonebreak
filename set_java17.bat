@echo off
REM Set up Java 17 environment variables for Stonebreak development

REM Set Java 17 path
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

echo Java environment set to Java 17:
java -version

echo JAVA_HOME set to: %JAVA_HOME%

echo You can now run Maven and other Java tools using Java 17.
cmd /k
