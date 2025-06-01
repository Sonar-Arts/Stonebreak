# Stonebreak

A 3D voxel-based sandbox game created with Java and LWJGL, inspired by Minecraft.

## Development Requirements

- Java 17 or higher is required to build and run this project
- Maven 3.6 or higher for dependency management and building

## Building and Running

### Using Java 17

This project requires Java 17 features. We've provided several convenience scripts:

1. **Windows Command Prompt**:
   ```
   mvn-with-java17.bat clean package
   ```

2. **PowerShell**:
   ```
   ./build-and-run-with-java17.ps1
   ```

3. **Direct Java Execution**:
   - Set Java 17 as your JAVA_HOME:
     ```
     $Env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
     $Env:PATH = "$Env:JAVA_HOME\bin;$Env:PATH"
     ```
   - Then build and run:
     ```
     mvn clean package
     java -jar target/stonebreak-1.0-SNAPSHOT.jar
     ```

