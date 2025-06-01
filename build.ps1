# Compile and run Stonebreak using Java 17

# Set Java 17 path
$Env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
$Env:PATH = "$Env:JAVA_HOME\bin;$Env:PATH"

# Set paths
$SRC_DIR = "src\main\java"
$OUT_DIR = "target\classes"
$LIB_DIR = "lib"
$CLASSPATH = "$LIB_DIR\*"

# Create output directory if it doesn't exist
New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null

Write-Host "Compiling project..."
javac -d $OUT_DIR -cp "$CLASSPATH" $SRC_DIR\com\stonebreak\*.java

if ($LASTEXITCODE -eq 0) {
    Write-Host "Compilation successful!"
    Write-Host "Starting Stonebreak..."
    java -cp "$OUT_DIR;$CLASSPATH" com.stonebreak.Main
} else {
    Write-Host "Compilation failed!"
}
